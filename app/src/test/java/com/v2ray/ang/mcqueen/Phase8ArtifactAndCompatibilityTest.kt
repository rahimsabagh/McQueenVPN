package com.v2ray.ang.mcqueen

import android.content.res.AssetManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.GeoDatInspector
import com.v2ray.ang.handler.SettingsManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

class Phase8ArtifactAndCompatibilityTest {

    private fun resolveProjectFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        val fromModule = File("..", relativePath)
        if (fromModule.exists()) return fromModule
        return File(System.getProperty("user.dir"), relativePath)
    }

    @Test
    fun `production ARM ABIs contain complete native library set and arm64-v8a binaries are 16 KB aligned`() {
        val libsDir = resolveProjectFile("app/libs").takeIf { it.isDirectory } ?: resolveProjectFile("libs")
        assertTrue(libsDir.isDirectory, "app/libs directory must exist at ${libsDir.absolutePath}")

        val productionAbis = listOf("arm64-v8a", "armeabi-v7a")
        val stagedLibraries = listOf(
            "libaether.so",
            "libpsiphon-tunnel-core.so",
            "liblyrebird.so",
            "libhev-socks5-tunnel.so",
            "libhevsockstun.so",
        )

        for (abi in productionAbis) {
            for (lib in stagedLibraries) {
                val soFile = File(libsDir, "$abi/$lib")
                assertTrue(soFile.isFile && soFile.length() > 0L, "Missing staged native library: $abi/$lib")
            }
        }

        val aarFile = File(libsDir, "libv2ray.aar")
        assertTrue(aarFile.isFile && aarFile.length() > 0L, "libv2ray.aar must exist")
        ZipFile(aarFile).use { zip ->
            for (abi in productionAbis) {
                val entry = zip.getEntry("jni/$abi/libgojni.so")
                assertTrue(entry != null && entry.size > 0L, "libv2ray.aar missing jni/$abi/libgojni.so")
            }

            // Verify 16 KB page alignment on arm64-v8a libgojni.so inside libv2ray.aar
            val arm64GoJniBytes = zip.getInputStream(zip.getEntry("jni/arm64-v8a/libgojni.so")).use { stream ->
                val header = ByteArray(4096)
                val read = stream.read(header)
                header.copyOf(read)
            }
            assertElf64PtLoad16KbAligned("jni/arm64-v8a/libgojni.so", arm64GoJniBytes)
        }

        for (lib in stagedLibraries) {
            val soFile = File(libsDir, "arm64-v8a/$lib")
            val headerBytes = soFile.inputStream().use { stream ->
                val buf = ByteArray(4096)
                val read = stream.read(buf)
                buf.copyOf(read)
            }
            assertElf64PtLoad16KbAligned("arm64-v8a/$lib", headerBytes)
        }
    }

    private fun assertElf64PtLoad16KbAligned(label: String, header: ByteArray) {
        assertTrue(header.size >= 64, "$label ELF header too short")
        assertEquals(0x7F.toByte(), header[0], "$label invalid ELF magic")
        assertEquals('E'.code.toByte(), header[1], "$label invalid ELF magic")
        assertEquals('L'.code.toByte(), header[2], "$label invalid ELF magic")
        assertEquals('F'.code.toByte(), header[3], "$label invalid ELF magic")
        assertEquals(2.toByte(), header[4], "$label must be 64-bit ELF (ELFCLASS64)")

        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val eMachine = bb.getShort(18).toInt() and 0xFFFF
        assertEquals(183, eMachine, "$label must target EM_AARCH64 (183)")

        val ePhoff = bb.getLong(32).toInt()
        val ePhentsize = bb.getShort(54).toInt() and 0xFFFF
        val ePhnum = bb.getShort(56).toInt() and 0xFFFF
        var ptLoadCount = 0

        for (i in 0 until ePhnum) {
            val off = ePhoff + i * ePhentsize
            val pType = bb.getInt(off)
            if (pType == 1) { // PT_LOAD
                ptLoadCount++
                val pOffset = bb.getLong(off + 8)
                val pVaddr = bb.getLong(off + 16)
                val pAlign = bb.getLong(off + 48)
                assertTrue(pAlign >= 16384L, "$label PT_LOAD[$i] p_align ($pAlign) must be >= 16384")
                assertEquals(
                    pOffset % 16384L,
                    pVaddr % 16384L,
                    "$label PT_LOAD[$i] p_vaddr and p_offset must be congruent modulo 16384"
                )
            }
        }
        assertTrue(ptLoadCount > 0, "$label must contain at least one PT_LOAD segment")
    }

    @Test
    fun `copyAssetAtomically writes complete file via temp file and leaves no leftover temp artifacts`(@TempDir tempDir: File) {
        val payload = "atomic-geo-asset-content-verification".repeat(256).toByteArray(Charsets.UTF_8)
        val assetManager = mock(AssetManager::class.java)
        `when`(assetManager.open(AppConfig.PSIPHON_SERVERS_DAT)).thenAnswer {
            ByteArrayInputStream(payload)
        }

        val expectedTimestamp = 1_760_000_000_000L
        SettingsManager.copyAssetAtomically(
            assets = assetManager,
            extDir = tempDir,
            assetName = AppConfig.PSIPHON_SERVERS_DAT,
            publishedAt = expectedTimestamp,
        )

        val target = File(tempDir, AppConfig.PSIPHON_SERVERS_DAT)
        assertTrue(target.isFile, "Target asset file must be created")
        assertEquals(payload.size.toLong(), target.length(), "Target asset file length must match source payload")
        assertTrue(
            tempDir.listFiles().orEmpty().none { it.name.contains(".tmp.") },
            "No temporary .tmp. files may remain after atomic copy"
        )
    }

    @Test
    fun `copyAssetAtomically replaces existing destination file and preserves existing file on copy failure`(@TempDir tempDir: File) {
        val target = File(tempDir, AppConfig.GEOIP_DAT)
        val initialBytes = "initial-valid-geoip-payload-v1".repeat(64).toByteArray(Charsets.UTF_8)
        target.writeBytes(initialBytes)

        // 1. Successful update replaces existing target atomically
        val updatedBytes = "updated-valid-geoip-payload-v2-longer".repeat(128).toByteArray(Charsets.UTF_8)
        val assetManager = mock(AssetManager::class.java)
        `when`(assetManager.open(AppConfig.GEOIP_DAT)).thenAnswer {
            ByteArrayInputStream(updatedBytes)
        }
        SettingsManager.copyAssetAtomically(
            assets = assetManager,
            extDir = tempDir,
            assetName = AppConfig.GEOIP_DAT,
        )
        assertTrue(target.readBytes().contentEquals(updatedBytes), "Target must contain updated payload after atomic replacement")
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.contains(".tmp.") })

        // 2. Failed stream mid-copy leaves existing target untouched and cleans up temp file
        `when`(assetManager.open(AppConfig.GEOIP_DAT)).thenAnswer {
            object : java.io.InputStream() {
                private var readBytesCount = 0
                override fun read(): Int {
                    if (readBytesCount++ < 128) return 'X'.code
                    throw java.io.IOException("Simulated low-storage I/O failure mid-copy")
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) {
            SettingsManager.copyAssetAtomically(
                assets = assetManager,
                extDir = tempDir,
                assetName = AppConfig.GEOIP_DAT,
            )
        }
        assertTrue(
            target.readBytes().contentEquals(updatedBytes),
            "Failed copy must never corrupt or overwrite an existing valid destination file"
        )
        assertTrue(
            tempDir.listFiles().orEmpty().none { it.name.contains(".tmp.") },
            "Failed copy must clean up its temporary file"
        )

        // 3. Stale .tmp. files left by an interrupted process are swept cleanly
        val staleTemp = File(tempDir, "geoip.dat.tmp.12345.orphaned")
        staleTemp.writeText("partial-interrupted-bytes")
        assertTrue(staleTemp.exists())
        SettingsManager.sweepStaleTempFiles(tempDir)
        assertFalse(staleTemp.exists(), "sweepStaleTempFiles must remove orphaned .tmp. files")
        assertTrue(target.isFile && target.readBytes().contentEquals(updatedBytes), "sweepStaleTempFiles must preserve valid target files")
    }

    @Test
    fun `GeoDatInspector short-circuit scan preserves exact IR CIDR and domain counts`() {
        val assetsDir = resolveProjectFile("app/src/main/assets")
            .takeIf { it.isDirectory }
            ?: resolveProjectFile("src/main/assets")
        val geoipStats = GeoDatInspector.inspectGeoIp(File(assetsDir, AppConfig.GEOIP_DAT), "IR")
        val geositeStats = GeoDatInspector.inspectGeoSite(File(assetsDir, AppConfig.GEOSITE_DAT), "CATEGORY-IR")

        assertTrue(geoipStats.validArchive)
        assertTrue(geoipStats.hasAnyCidrs)
        assertTrue(geoipStats.ipv4CidrCount > 500)
        assertTrue(geoipStats.ipv6CidrCount > 100)

        assertTrue(geositeStats.validArchive)
        assertTrue(geositeStats.hasDomains)
        assertTrue(geositeStats.domainCount > 500)
    }

    @Test
    fun `GitHub workflows enforce explicit debug and release naming and validate production ABI inventory`() {
        val workflowPaths = listOf(
            ".github/workflows/build.yml",
            ".github/workflows/release.yml",
            ".github/workflows/android.yml",
        )
        for (relPath in workflowPaths) {
            val wfFile = resolveProjectFile(relPath)
            assertTrue(wfFile.isFile, "$relPath must exist")
            val content = wfFile.readText(Charsets.UTF_8)

            assertFalse(
                content.contains("using debug-signed APK as release artifact"),
                "$relPath must never alias a debug-signed APK as a release artifact"
            )
            assertFalse(
                content.contains("debug.apk\" \"dist/app-release.apk\""),
                "$relPath must never copy a debug APK to dist/app-release.apk"
            )
            assertTrue(content.contains("v1.0.0-beta.4"), "$relPath must default to v1.0.0-beta.4")
            assertTrue(content.contains("expected_vcode=\"764\""), "$relPath must validate versionCode 764")
            assertTrue(content.contains("chmod 600"), "$relPath must restrict keystore permissions")
            assertTrue(content.contains("rm -f \"\$RUNNER_TEMP/release.jks\""), "$relPath must clean up temporary keystore")
            assertTrue(content.contains("-c -P 16 -v 4"), "$relPath must validate 16 KB zipalign")
            assertTrue(content.contains("verify --verbose --print-certs"), "$relPath must verify APK signatures")

            for (lib in listOf("libaether.so", "libgojni.so", "libpsiphon-tunnel-core.so", "liblyrebird.so", "libhev-socks5-tunnel.so", "libhevsockstun.so", "libmmkv.so")) {
                assertTrue(content.contains(lib), "$relPath must validate presence of $lib")
            }
        }
    }
}
