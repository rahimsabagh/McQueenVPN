package com.v2ray.ang.core

import android.content.Context
import android.content.ContextWrapper
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherFingerprint
import com.v2ray.ang.enums.AetherKeyKind
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class AetherIdentityProvisionerTest {

    private fun testContext(filesDir: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = filesDir
    }

    private fun provisionerFor(
        backend: AetherIdentityBackend,
        clockMs: () -> Long = System::currentTimeMillis,
    ): AetherIdentityProvisioner = AetherIdentityProvisioner(
        backend = backend,
        clockMs = clockMs,
        logger = { _, _ -> },
    )

    private fun sampleUsAetherProfile(): ProfileItem =
        AetherFmt.parse("aether://?protocol=wg&scan=balanced&ip=v4&psiphon=chain&psiphon_mode=auto&region=US#aa")
            ?: error("Expected valid aether URI")

    private fun wgTomlFor(
        deviceId: String,
        ipv4: String = "172.16.0.2",
        ipv6: String = "2606:4700:110::2",
        privateKey: String = "secret_private_key_value",
        accessToken: String = "secret_token_value",
    ): String =
        """
        device_id = "$deviceId"
        access_token = "$accessToken"
        ipv4 = "$ipv4"
        ipv6 = "$ipv6"
        wg_private_key = "$privateKey"
        """.trimIndent()

    private fun masqueTomlFor(
        deviceId: String,
        ipv4: String = "172.16.0.2",
        ipv6: String = "2606:4700:110::2",
        accessToken: String = "secret_token_value",
        certBody: String = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A",
    ): String {
        val quotes = "\"\"\""
        return listOf(
            "device_id = \"$deviceId\"",
            "access_token = \"$accessToken\"",
            "cert_pem = $quotes",
            "-----BEGIN CERTIFICATE-----",
            "device_id = \"hidden-in-pem\"",
            certBody,
            "-----END CERTIFICATE-----",
            quotes,
            "cert_issued_at = 1757580000",
            "ipv4 = \"$ipv4\"",
            "ipv6 = \"$ipv6\"",
        ).joinToString("\n")
    }

    private fun tomlForFile(fileName: String, deviceId: String): String = when (fileName) {
        AetherIdentityManager.MASQUE_FILE,
        AetherIdentityManager.MASQUE_INNER_FILE,
        -> masqueTomlFor(deviceId)
        else -> wgTomlFor(deviceId)
    }

    private class FakeIdentityBackend(
        private val filesDir: File,
        var supported: Boolean = true,
        var forceSettleSuccess: Boolean? = null,
        var preserveValidExisting: Boolean = true,
    ) : AetherIdentityBackend {
        var registerCalls: Int = 0
        var lastKind: AetherKeyKind? = null
        var lastArguments: List<String> = emptyList()
        var lastExit: AetherExit? = null
        var overrideRenewKeys: List<AetherKey>? = null
        var renewHandler: suspend (
            workDir: File,
            renewalDir: File,
            kind: AetherKeyKind,
            files: List<String>,
            onOutput: (String) -> Unit,
        ) -> Boolean = { _, _, _, _, _ -> false }

        override fun isSupported(context: Context): Boolean = supported

        override fun settle(context: Context): Boolean =
            forceSettleSuccess ?: AetherIdentityManager.settleIn(filesDir)

        override fun missingReadable(context: Context, files: List<String>): List<String> {
            val workDir = File(filesDir, "aether")
            val unparseable = AetherIdentityManager.missing(workDir, files).toSet()
            return files.filter { name ->
                val f = File(workDir, name)
                name in unparseable || !f.isFile || !f.canRead()
            }
        }

        override suspend fun renew(
            context: Context,
            kind: AetherKeyKind,
            arguments: List<String>,
            exit: AetherExit,
            onOutput: (String) -> Unit,
        ): List<AetherKey>? {
            registerCalls++
            lastKind = kind
            lastArguments = arguments
            lastExit = exit
            val workDir = File(filesDir, "aether")
            val renewalDir = File(filesDir, "aether-renewal")
            val files = AetherIdentityManager.filesOf(kind)
            val ok = AetherIdentityManager.renew(
                workDir = workDir,
                renewalDir = renewalDir,
                files = files,
                preserveValidExisting = preserveValidExisting,
            ) {
                renewHandler(workDir, renewalDir, kind, files, onOutput)
            }
            if (!ok) return null
            return overrideRenewKeys ?: AetherIdentityManager.keys(workDir, files)
        }
    }

    @Test
    fun `fresh install registers identity, persists readable file, and second connection plus restart reuse it`(@TempDir tempDir: File) = runTest {
        val ctx = testContext(tempDir)
        val profile = sampleUsAetherProfile()
        val backend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, renewalDir, _, files, onOutput ->
                onOutput("[2026-11-14 INFO aether] identities ready: wg")
                files.forEach { fileName ->
                    File(renewalDir, fileName).writeText(tomlForFile(fileName, deviceId = "dev-first-install"))
                }
                true
            }
        }
        val provisioner = provisionerFor(backend)

        // 1. Fresh install -> no key exists yet, must register
        assertFalse(provisioner.hasValidIdentity(ctx, profile))
        val firstResult = provisioner.ensureIdentity(ctx, profile)
        assertTrue(firstResult is AetherProvisionResult.Provisioned)
        assertEquals(listOf(AetherIdentityManager.WIREGUARD_FILE), (firstResult as AetherProvisionResult.Provisioned).requiredFiles)
        assertEquals(1, backend.registerCalls)
        assertEquals(AetherKeyKind.WIREGUARD, backend.lastKind)
        assertTrue(backend.lastArguments.contains("--register"))
        assertTrue(backend.lastArguments.contains("wg"))

        val persistedFile = File(File(tempDir, "aether"), AetherIdentityManager.WIREGUARD_FILE)
        assertTrue(persistedFile.isFile && persistedFile.canRead())
        assertEquals("dev-first-install", AetherIdentityManager.parse(persistedFile.readText(), persistedFile.name)?.deviceId)

        // 2. Second connection -> must reuse without registering again
        assertTrue(provisioner.hasValidIdentity(ctx, profile))
        val secondResult = provisioner.ensureIdentity(ctx, profile)
        assertTrue(secondResult is AetherProvisionResult.AlreadyReady)
        assertEquals(1, backend.registerCalls)

        // 3. Simulated app restart (new provisioner + fresh backend reading same filesDir)
        val restartedBackend = FakeIdentityBackend(tempDir)
        val restartedProvisioner = provisionerFor(restartedBackend)
        val afterRestart = restartedProvisioner.ensureIdentity(ctx, profile)
        assertTrue(afterRestart is AetherProvisionResult.AlreadyReady)
        assertEquals(0, restartedBackend.registerCalls)
        assertEquals("dev-first-install", AetherIdentityManager.parse(persistedFile.readText(), persistedFile.name)?.deviceId)
    }

    @Test
    fun `failed renewal never overwrites or deletes existing valid identity`(@TempDir tempDir: File) = runTest {
        val ctx = testContext(tempDir)
        val workDir = File(tempDir, "aether").apply { mkdirs() }
        val masqueFile = File(workDir, AetherIdentityManager.MASQUE_FILE)
        masqueFile.writeText(masqueTomlFor(deviceId = "existing-valid-masque"))

        val backend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, renewalDir, _, files, onOutput ->
                // Simulate failed partial write inside renewalDir
                File(renewalDir, files.first()).writeText(masqueTomlFor(deviceId = "partial-bad"))
                onOutput("identity refused: 403 Forbidden")
                false
            }
        }
        val profile = ProfileItem(
            configType = EConfigType.AETHER,
            aetherProtocol = AetherProtocol.MIM.type, // needs primary + secondary MASQUE keys (secondary missing)
        )
        val provisioner = provisionerFor(backend)
        val result = provisioner.ensureIdentity(ctx, profile)
        assertTrue(result is AetherProvisionResult.Failed)
        assertEquals(ProvisionFailureKind.REGISTRATION_REJECTED, (result as AetherProvisionResult.Failed).kind)

        // Existing valid MASQUE key must remain completely untouched
        assertEquals(
            "existing-valid-masque",
            AetherIdentityManager.parse(masqueFile.readText(), AetherIdentityManager.MASQUE_FILE)?.deviceId,
        )
    }

    @Test
    fun `partial two-hop provisioning preserves already-valid primary identity without regenerating`(@TempDir tempDir: File) = runTest {
        val ctx = testContext(tempDir)
        val workDir = File(tempDir, "aether").apply { mkdirs() }
        val primaryFile = File(workDir, AetherIdentityManager.MASQUE_FILE)
        val initialPrimaryContent = masqueTomlFor(deviceId = "primary-masque-preserved", certBody = "EXISTINGCERTBODY")
        primaryFile.writeText(initialPrimaryContent)

        // Case A: MIM missing secondary MASQUE identity
        val backend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, renewalDir, _, files, onOutput ->
                onOutput("[2026-11-14 INFO aether] identities ready: masque, masque inner")
                // Aether --register mim registers both files into renewalDir, but existing primary in workDir must stay untouched
                files.forEach { fileName ->
                    File(renewalDir, fileName).writeText(tomlForFile(fileName, deviceId = "newly-registered-$fileName"))
                }
                true
            }
        }
        val mimProfile = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = AetherProtocol.MIM.type)
        val provisioner = provisionerFor(backend)

        val mimResult = provisioner.ensureIdentity(ctx, mimProfile)
        assertTrue(mimResult is AetherProvisionResult.Provisioned)
        assertEquals(AetherKeyKind.MIM, backend.lastKind)
        assertEquals(listOf("--register", "mim"), backend.lastArguments.take(2))
        assertEquals(initialPrimaryContent, primaryFile.readText())
        val secondaryFile = File(workDir, AetherIdentityManager.MASQUE_INNER_FILE)
        assertTrue(secondaryFile.isFile)
        assertEquals(
            "newly-registered-${AetherIdentityManager.MASQUE_INNER_FILE}",
            AetherIdentityManager.parse(secondaryFile.readText(), AetherIdentityManager.MASQUE_INNER_FILE)?.deviceId,
        )

        // Case B: WG_OVER_MASQUE missing aether-masque-gool.toml while primary MASQUE is still valid
        val goolProfile = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = AetherProtocol.WG_OVER_MASQUE.type)
        val goolResult = provisioner.ensureIdentity(ctx, goolProfile)
        assertTrue(goolResult is AetherProvisionResult.Provisioned)
        assertEquals(AetherKeyKind.WG_OVER_MASQUE, backend.lastKind)
        assertEquals(listOf("--register", "gool"), backend.lastArguments.take(2))
        assertEquals(initialPrimaryContent, primaryFile.readText())
        val goolFile = File(workDir, AetherIdentityManager.MASQUE_GOOL_FILE)
        assertEquals(
            "newly-registered-${AetherIdentityManager.MASQUE_GOOL_FILE}",
            AetherIdentityManager.parse(goolFile.readText(), AetherIdentityManager.MASQUE_GOOL_FILE)?.deviceId,
        )
    }

    @Test
    fun `registrationArguments emits exact registration modes and forwards carrier, API, fragment, ECH, and TLS flags`() {
        val protocolExpectations = mapOf(
            AetherProtocol.WIREGUARD to "wg",
            AetherProtocol.MASQUE to "masque",
            AetherProtocol.GOOL to "gool-classic",
            AetherProtocol.MIM to "mim",
            AetherProtocol.WG_OVER_MASQUE to "gool",
        )
        for ((protocol, expectedRegisterMode) in protocolExpectations) {
            val profile = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = protocol.type)
            val core = AetherCore.of(profile)
            val args = AetherIdentityProvisioner.registrationArguments(core)
            assertEquals(listOf("--register", expectedRegisterMode), args.take(2), protocol.name)
            assertFalse("--bind" in args, "registration must not bind tunnel SOCKS listener")
            assertFalse("--scan" in args, "registration must not include scan options")
            assertFalse("--protocol" in args, "registration must use --register mode, not --protocol")
        }

        // Verify reverse Tor (with bridges & relays), reverse Psiphon (with custom entries & CDN), API overrides,
        // fragment, ECH, and custom TLS fingerprint are all preserved accurately.
        val customCore = AetherCore.ofCommand(
            "aether --protocol mim --h2 --api-address 162.159.137.105:443 --api-sni api.cloudflareclient.com " +
                "--api-host api.cloudflareclient.com --fragment --fragment-size 8-16 --fragment-delay 2-5 " +
                "--ech auto --ech-dns https://1.1.1.1/dns-query --ech-domain cloudflare-ech.com " +
                "--tor-reverse --tor-bind 127.0.0.1:0 --tor-bridges --tor-bridge \"obfs4 1.2.3.4:443\" --tor-relays only " +
                "--psiphon-reverse --psiphon-bind 127.0.0.1:0 --psiphon-mode cdn --psiphon-cdn-ips 1.1.1.1 " +
                "--psiphon-cdn-sni cdn.example --psiphon-cdn-sets cloudflare,fastly --psiphon-region US " +
                "--psiphon-server-entries /custom/entries.dat " +
                AetherFingerprint.FIREFOX.arguments.joinToString(" "),
        )!!

        val regArgs = AetherIdentityProvisioner.registrationArguments(customCore)
        assertEquals(listOf("--register", "mim"), regArgs.take(2))
        assertEquals("162.159.137.105:443", AetherCoreManager.valueAfter(regArgs, "--api-address"))
        assertEquals("api.cloudflareclient.com", AetherCoreManager.valueAfter(regArgs, "--api-sni"))
        assertEquals("api.cloudflareclient.com", AetherCoreManager.valueAfter(regArgs, "--api-host"))
        assertTrue("--fragment" in regArgs)
        assertEquals("8-16", AetherCoreManager.valueAfter(regArgs, "--fragment-size"))
        assertEquals("2-5", AetherCoreManager.valueAfter(regArgs, "--fragment-delay"))
        assertEquals("auto", AetherCoreManager.valueAfter(regArgs, "--ech"))
        assertEquals("https://1.1.1.1/dns-query", AetherCoreManager.valueAfter(regArgs, "--ech-dns"))
        assertEquals("cloudflare-ech.com", AetherCoreManager.valueAfter(regArgs, "--ech-domain"))
        assertEquals(AetherFingerprint.FIREFOX.ciphers, AetherCoreManager.valueAfter(regArgs, "--tls-ciphers"))
        assertTrue("--disable-session-ticket" in regArgs)
        assertFalse("--enable-grease-sigalgs" in regArgs)

        assertTrue("--tor-reverse" in regArgs)
        assertEquals("127.0.0.1:0", AetherCoreManager.valueAfter(regArgs, "--tor-bind"))
        assertTrue("--tor-bridges" in regArgs)
        assertEquals("obfs4 1.2.3.4:443", AetherCoreManager.valueAfter(regArgs, "--tor-bridge"))
        assertEquals("only", AetherCoreManager.valueAfter(regArgs, "--tor-relays"))

        assertTrue("--psiphon-reverse" in regArgs)
        assertEquals("127.0.0.1:0", AetherCoreManager.valueAfter(regArgs, "--psiphon-bind"))
        assertEquals("cdn", AetherCoreManager.valueAfter(regArgs, "--psiphon-mode"))
        assertEquals("1.1.1.1", AetherCoreManager.valueAfter(regArgs, "--psiphon-cdn-ips"))
        assertEquals("cdn.example", AetherCoreManager.valueAfter(regArgs, "--psiphon-cdn-sni"))
        assertEquals("cloudflare,fastly", AetherCoreManager.valueAfter(regArgs, "--psiphon-cdn-sets"))
        assertEquals("US", AetherCoreManager.valueAfter(regArgs, "--psiphon-region"))
        assertEquals("/custom/entries.dat", AetherCoreManager.valueAfter(regArgs, "--psiphon-server-entries"))
    }

    @Test
    fun `identityEnvironment aligns Aether process env vars with AetherIdentityManager persistence paths`(@TempDir tempDir: File) {
        val ctx = testContext(tempDir)
        val workDir = AetherIdentityManager.workDir(ctx)
        val renewalDir = File(tempDir, "aether-renewal")

        val renewalEnv = AetherCoreManager.identityEnvironment(workDir, renewalDir)
        assertEquals(workDir.absolutePath, renewalEnv["HOME"])
        assertEquals(File(workDir, "aether.toml").absolutePath, renewalEnv["AETHER_CONFIG"])
        assertEquals(File(renewalDir, "aether-masque.toml").absolutePath, renewalEnv["AETHER_MASQUE_CONFIG"])
        assertEquals(File(renewalDir, "aether-wg.toml").absolutePath, renewalEnv["AETHER_WG_CONFIG"])

        // Normal tunnel operation uses permanent storage workDir for key env vars too.
        val tunnelEnv = AetherCoreManager.identityEnvironment(workDir, workDir)
        assertEquals(File(workDir, "aether-masque.toml").absolutePath, tunnelEnv["AETHER_MASQUE_CONFIG"])
        assertEquals(File(workDir, "aether-wg.toml").absolutePath, tunnelEnv["AETHER_WG_CONFIG"])

        // Sibling derived key paths match Aether v2.4.0 conventions inside workDir and renewalDir.
        val masqueBase = File(tunnelEnv.getValue("AETHER_MASQUE_CONFIG")).parentFile!!
        val wgBase = File(tunnelEnv.getValue("AETHER_WG_CONFIG")).parentFile!!
        assertEquals(
            File(workDir, AetherIdentityManager.MASQUE_INNER_FILE).absolutePath,
            File(masqueBase, "aether-masque-secondary.toml").absolutePath,
        )
        assertEquals(
            File(workDir, AetherIdentityManager.MASQUE_GOOL_FILE).absolutePath,
            File(masqueBase, "aether-masque-gool.toml").absolutePath,
        )
        assertEquals(
            File(workDir, AetherIdentityManager.WIREGUARD_INNER_FILE).absolutePath,
            File(wgBase, "aether-wg-secondary.toml").absolutePath,
        )
    }

    @Test
    fun `incomplete device-only or un-enrolled identity files are rejected and never treated as registered`(@TempDir tempDir: File) = runTest {
        val ctx = testContext(tempDir)
        val workDir = File(tempDir, "aether").apply { mkdirs() }

        // 1. Device registered only (pre-enrollment checkpoint persisted by Aether v2.4.0)
        val deviceOnly = """
            device_id = "device-only-123"
            access_token = "tok-123"
        """.trimIndent()
        assertNull(AetherIdentityManager.parse(deviceOnly, AetherIdentityManager.WIREGUARD_FILE))
        assertNull(AetherIdentityManager.parse(deviceOnly, AetherIdentityManager.MASQUE_FILE))

        // 2. MASQUE device registered + IP assigned, but missing cert_pem (enrollment incomplete)
        val masqueWithoutCert = """
            device_id = "masque-no-cert"
            access_token = "tok-123"
            ipv4 = "172.16.0.2"
            ipv6 = "2606:4700:110::2"
            wg_private_key = "ignored-for-masque"
        """.trimIndent()
        assertNull(AetherIdentityManager.parse(masqueWithoutCert, AetherIdentityManager.MASQUE_FILE))
        assertNull(AetherIdentityManager.parse(masqueWithoutCert, AetherIdentityManager.MASQUE_INNER_FILE))

        // 3. WireGuard missing wg_private_key or missing access_token or missing IPs
        val wgWithoutKey = """
            device_id = "wg-no-key"
            access_token = "tok-123"
            ipv4 = "172.16.0.2"
        """.trimIndent()
        assertNull(AetherIdentityManager.parse(wgWithoutKey, AetherIdentityManager.WIREGUARD_FILE))
        assertNull(AetherIdentityManager.parse(wgWithoutKey, AetherIdentityManager.MASQUE_GOOL_FILE))

        val wgWithoutToken = wgTomlFor(deviceId = "wg-no-token", accessToken = "")
        assertNull(AetherIdentityManager.parse(wgWithoutToken, AetherIdentityManager.WIREGUARD_FILE))

        val wgWithoutIp = wgTomlFor(deviceId = "wg-no-ip", ipv4 = "", ipv6 = "")
        assertNull(AetherIdentityManager.parse(wgWithoutIp, AetherIdentityManager.WIREGUARD_FILE))

        // 4. Write partial MASQUE file on disk -> hasValidIdentity MUST be false and ensureIdentity MUST NOT return AlreadyReady
        File(workDir, AetherIdentityManager.MASQUE_FILE).writeText(masqueWithoutCert)
        val masqueProfile = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = AetherProtocol.MASQUE.type)

        val partialRenewBackend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, renewalDir, _, files, onOutput ->
                // Simulate process logging ready line, yet writing un-enrolled MASQUE cert-less TOML
                onOutput("identities ready: masque")
                files.forEach { File(renewalDir, it).writeText(masqueWithoutCert) }
                true
            }
        }
        val provisioner = provisionerFor(partialRenewBackend)
        assertFalse(provisioner.hasValidIdentity(ctx, masqueProfile))
        val failResult = provisioner.ensureIdentity(ctx, masqueProfile)
        assertTrue(failResult is AetherProvisionResult.Failed)
        assertEquals(ProvisionFailureKind.IDENTITY_PERSISTENCE_FAILURE, (failResult as AetherProvisionResult.Failed).kind)
        // Interrupted/un-enrolled partial file must never be promoted from renewalDir over workDir
        assertFalse(File(tempDir, "aether-renewal").exists())

        // 5. Also verify empty renewedKeys returned by backend triggers IDENTITY_MISSING even if files exist
        File(workDir, AetherIdentityManager.MASQUE_FILE).delete()
        val emptyReturnBackend = FakeIdentityBackend(tempDir).apply {
            overrideRenewKeys = emptyList()
            renewHandler = { _, renewalDir, _, files, onOutput ->
                onOutput("identities ready: masque")
                files.forEach { File(renewalDir, it).writeText(masqueTomlFor("valid-id")) }
                true
            }
        }
        val missingReturnedResult = provisionerFor(emptyReturnBackend).ensureIdentity(ctx, masqueProfile)
        assertTrue(missingReturnedResult is AetherProvisionResult.Failed)
        assertEquals(ProvisionFailureKind.IDENTITY_MISSING, (missingReturnedResult as AetherProvisionResult.Failed).kind)
    }

    @Test
    fun `concurrent ensureIdentity calls only register once and propagate core exit`(@TempDir tempDir: File) = runTest {
        val ctx = testContext(tempDir)
        val calls = AtomicInteger(0)
        val backend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, renewalDir, _, files, onOutput ->
                calls.incrementAndGet()
                delay(40)
                onOutput("identities ready: wg")
                files.forEach { File(renewalDir, it).writeText(wgTomlFor("concurrent-id")) }
                true
            }
        }
        val provisioner = provisionerFor(backend)
        val profile = ProfileItem(
            configType = EConfigType.AETHER,
            aetherProtocol = AetherProtocol.WIREGUARD.type,
            finalMask = """{"tcp":[]}""",
            dialMode = "ForceIP",
        )
        val expectedExit = AetherCore.of(profile).exit

        val first = async { provisioner.ensureIdentity(ctx, profile) }
        val second = async { provisioner.ensureIdentity(ctx, profile) }
        val r1 = first.await()
        val r2 = second.await()

        assertEquals(1, calls.get())
        assertEquals(expectedExit, backend.lastExit)
        assertTrue(setOf(r1::class, r2::class) == setOf(AetherProvisionResult.Provisioned::class, AetherProvisionResult.AlreadyReady::class))
    }

    @Test
    fun `recovers after temporary API unavailability and persists on subsequent connect`(@TempDir tempDir: File) = runTest {
        val ctx = testContext(tempDir)
        val profile = sampleUsAetherProfile()
        var attempt = 0
        val backend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, renewalDir, _, files, onOutput ->
                attempt++
                if (attempt == 1) {
                    onOutput("api: failed to lookup address information: temporary failure in name resolution")
                    false
                } else {
                    onOutput("identities ready: wg")
                    files.forEach { File(renewalDir, it).writeText(wgTomlFor(deviceId = "recovered-device")) }
                    true
                }
            }
        }
        val provisioner = provisionerFor(backend)

        val firstAttempt = provisioner.ensureIdentity(ctx, profile)
        assertTrue(firstAttempt is AetherProvisionResult.Failed)
        assertEquals(ProvisionFailureKind.REGISTRATION_API_UNREACHABLE, (firstAttempt as AetherProvisionResult.Failed).kind)

        val secondAttempt = provisioner.ensureIdentity(ctx, profile)
        assertTrue(secondAttempt is AetherProvisionResult.Provisioned)
        assertEquals(2, backend.registerCalls)
        assertEquals(
            "recovered-device",
            AetherIdentityManager.parse(
                File(File(tempDir, "aether"), AetherIdentityManager.WIREGUARD_FILE).readText(),
                AetherIdentityManager.WIREGUARD_FILE,
            )?.deviceId,
        )
    }

    @Test
    fun `classifies all identity and tunnel failures distinctly and skips carrier-only modes`(@TempDir tempDir: File) = runTest {
        val ctx = testContext(tempDir)
        val profile = sampleUsAetherProfile()

        // 1. Psiphon-only / Tor-only -> NotRequired, zero registration calls
        val psiphonOnly = ProfileItem(configType = EConfigType.AETHER, aetherPsiphon = AetherPsiphon.ONLY.type)
        val torOnly = ProfileItem(configType = EConfigType.AETHER, aetherTor = AetherTor.ONLY.type)
        val idleBackend = FakeIdentityBackend(tempDir)
        val idleProvisioner = provisionerFor(idleBackend)
        assertEquals(AetherProvisionResult.NotRequired, idleProvisioner.ensureIdentity(ctx, psiphonOnly))
        assertEquals(AetherProvisionResult.NotRequired, idleProvisioner.ensureIdentity(ctx, torOnly))
        assertEquals(0, idleBackend.registerCalls)

        // 2. Process startup failure when libaether.so is unsupported
        val unsupportedBackend = FakeIdentityBackend(tempDir, supported = false)
        val unsupportedResult = provisionerFor(unsupportedBackend).ensureIdentity(ctx, profile)
        assertEquals(ProvisionFailureKind.PROCESS_STARTUP_FAILURE, (unsupportedResult as AetherProvisionResult.Failed).kind)

        // 3. Process startup failure when zero output and fast exit
        var fakeClock = 1_000L
        val fastCrashBackend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, _, _, _, _ ->
                fakeClock += 200L
                false
            }
        }
        val fastCrash = provisionerFor(fastCrashBackend, clockMs = { fakeClock }).ensureIdentity(ctx, profile)
        assertEquals(ProvisionFailureKind.PROCESS_STARTUP_FAILURE, (fastCrash as AetherProvisionResult.Failed).kind)

        // 4. Registration rejected
        val rejectedBackend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, _, _, _, onOutput ->
                onOutput("identity refused: rate limit exceeded HTTP 429")
                false
            }
        }
        val rejected = provisionerFor(rejectedBackend).ensureIdentity(ctx, profile)
        assertEquals(ProvisionFailureKind.REGISTRATION_REJECTED, (rejected as AetherProvisionResult.Failed).kind)

        // 5. Identity persistence failure (API reported identities ready, but file could not persist)
        val persistFailBackend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, _, _, _, onOutput ->
                onOutput("identities ready: wg")
                // Does not write files -> isComplete returns false -> renew returns null
                true
            }
        }
        val persistFail = provisionerFor(persistFailBackend).ensureIdentity(ctx, profile)
        assertEquals(ProvisionFailureKind.IDENTITY_PERSISTENCE_FAILURE, (persistFail as AetherProvisionResult.Failed).kind)

        // 6. Identity missing when register succeeded on temp files but disappeared from workDir after settle
        val missingAfterBackend = FakeIdentityBackend(tempDir).apply {
            renewHandler = { _, renewalDir, _, files, onOutput ->
                onOutput("identities ready: wg")
                files.forEach { File(renewalDir, it).writeText(wgTomlFor("temp-id")) }
                true
            }
        }
        val deletingBackend = object : AetherIdentityBackend by missingAfterBackend {
            override fun missingReadable(context: Context, files: List<String>): List<String> {
                // First pre-check says missing; post-check also reports missing
                return files
            }
        }
        val missingResult = provisionerFor(deletingBackend).ensureIdentity(ctx, profile)
        assertEquals(ProvisionFailureKind.IDENTITY_MISSING, (missingResult as AetherProvisionResult.Failed).kind)

        // 7. Tunnel handshake failure classification
        assertEquals(
            TunnelFailureKind.TUNNEL_HANDSHAKE_FAILURE,
            AetherIdentityProvisioner.classifyTunnelFailure("Tunnel handshake failed or timed out"),
        )
    }

    @Test
    fun `sanitizes sensitive keys tokens device ids and proxy uris from diagnostics`() {
        val raw = "Error device_id=\"1111-2222\" wg_private_key=abc123XYZ access_token=tok999 ipv4=172.16.0.2 uri=aether://?protocol=wg#secret"
        val sanitized = AetherIdentityProvisioner.sanitizeDiagnosticLine(raw)

        assertFalse(sanitized.contains("1111-2222"))
        assertFalse(sanitized.contains("abc123XYZ"))
        assertFalse(sanitized.contains("tok999"))
        assertFalse(sanitized.contains("172.16.0.2"))
        assertFalse(sanitized.contains("protocol=wg#secret"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }
}
