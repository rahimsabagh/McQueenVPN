package com.v2ray.ang.mcqueen

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)

        val thisHasPre = preRelease.isNotEmpty()
        val otherHasPre = other.preRelease.isNotEmpty()
        if (!thisHasPre && !otherHasPre) return 0
        if (!thisHasPre) return 1
        if (!otherHasPre) return -1

        val maxLen = maxOf(preRelease.size, other.preRelease.size)
        for (i in 0 until maxLen) {
            val a = preRelease.getOrNull(i) ?: return -1
            val b = other.preRelease.getOrNull(i) ?: return 1

            val aNum = a.toIntOrNull()
            val bNum = b.toIntOrNull()
            val cmp = when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1
                bNum != null -> 1
                else -> a.compareTo(b, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun toString(): String {
        val base = "$major.$minor.$patch"
        return if (preRelease.isEmpty()) base else "$base-${preRelease.joinToString(".")}"
    }

    companion object {
        private val SEMVER_REGEX = Regex(
            """^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$"""
        )

        fun parse(raw: String): SemanticVersion? {
            val cleaned = raw.trim()
            val match = SEMVER_REGEX.matchEntire(cleaned) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
            val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            val preRaw = match.groupValues[4]
            val preParts = if (preRaw.isEmpty()) {
                emptyList()
            } else {
                preRaw.split('.').filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() } ?: return null
            }
            return SemanticVersion(major, minor, patch, preParts)
        }

        fun isNewer(installedRaw: String, latestRaw: String): Boolean? {
            val installed = parse(installedRaw) ?: return null
            val latest = parse(latestRaw) ?: return null
            return latest > installed
        }
    }
}

data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val contentType: String = "",
    val sizeBytes: Long = 0L,
)

data class GitHubReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val publishedAtRaw: String,
    val htmlUrl: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubReleaseAsset>,
)

sealed class AppUpdateCheckResult {
    data object Idle : AppUpdateCheckResult()
    data object Checking : AppUpdateCheckResult()

    data class UpdateAvailable(
        val installedVersion: String,
        val latestVersion: String,
        val releaseNotes: String,
        val publishedAtFormatted: String,
        val apkAssetName: String,
        val apkDownloadUrl: String,
        val apkSizeBytes: Long,
        val releaseHtmlUrl: String,
    ) : AppUpdateCheckResult()

    data class UpToDate(
        val installedVersion: String,
        val latestVersion: String,
    ) : AppUpdateCheckResult()

    data object NoInternet : AppUpdateCheckResult()

    data class RateLimited(
        val resetEpochSeconds: Long? = null,
    ) : AppUpdateCheckResult()

    data class InvalidResponse(
        val detail: String,
    ) : AppUpdateCheckResult()

    data class MissingApkAsset(
        val latestVersion: String,
    ) : AppUpdateCheckResult()
}

object GitHubReleaseParser {
    private val ALLOWED_GITHUB_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    fun parseLatestRelease(json: String): GitHubReleaseInfo? {
        if (json.isBlank()) return null
        val obj = runCatching {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: return null

        val tagName = obj.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (tagName.isEmpty() || SemanticVersion.parse(tagName) == null) {
            return null
        }

        val draft = obj.get("draft")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (draft) return null

        val prerelease = obj.get("prerelease")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val releaseName = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            .ifEmpty { tagName }
        val releaseNotes = obj.get("body")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val publishedAt = obj.get("published_at")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val htmlUrl = obj.get("html_url")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()

        val assetsArray = obj.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray
        val assets = mutableListOf<GitHubReleaseAsset>()
        assetsArray?.forEach { elem ->
            val assetObj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val name = assetObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val url = assetObj.get("browser_download_url")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val contentType = assetObj.get("content_type")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val size = assetObj.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            if (name.isNotEmpty() && url.isNotEmpty()) {
                assets.add(
                    GitHubReleaseAsset(
                        name = name,
                        downloadUrl = url,
                        contentType = contentType,
                        sizeBytes = size,
                    )
                )
            }
        }

        return GitHubReleaseInfo(
            tagName = tagName,
            releaseName = releaseName,
            releaseNotes = releaseNotes,
            publishedAtRaw = publishedAt,
            htmlUrl = htmlUrl,
            draft = draft,
            prerelease = prerelease,
            assets = assets,
        )
    }

    fun isOfficialGitHubAssetUrl(rawUrl: String): Boolean {
        val uri = runCatching { java.net.URI(rawUrl.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host in ALLOWED_GITHUB_HOSTS
    }

    fun selectBestApkAsset(
        assets: List<GitHubReleaseAsset>,
        supportedAbis: List<String> = defaultSupportedAbis(),
    ): GitHubReleaseAsset? {
        val validApks = assets.filter { asset ->
            val lower = asset.name.lowercase(Locale.US)
            lower.endsWith(".apk") &&
                !lower.endsWith(".apk.sha256") &&
                !lower.endsWith(".apk.sig") &&
                !lower.endsWith(".apk.asc") &&
                isOfficialGitHubAssetUrl(asset.downloadUrl)
        }
        if (validApks.isEmpty()) return null

        // 1. Match highest-priority device ABI if ABI-specific APKs are attached
        val normalizedAbis = supportedAbis.map { it.trim().lowercase(Locale.US) }.filter { it.isNotEmpty() }
        for (abi in normalizedAbis) {
            val matched = validApks.firstOrNull { asset ->
                matchesAbiToken(asset.name.lowercase(Locale.US), abi)
            }
            if (matched != null) return matched
        }

        // 2. Fallback to explicit universal APK
        val universal = validApks.firstOrNull { asset ->
            asset.name.lowercase(Locale.US).contains("universal")
        }
        if (universal != null) return universal

        // 3. Fallback to generic single/release APK that is NOT tagged for a different ABI
        val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val genericReleaseApk = validApks.firstOrNull { asset ->
            val lower = asset.name.lowercase(Locale.US)
            knownAbis.none { token -> matchesAbiToken(lower, token) }
        }
        return genericReleaseApk
    }

    private fun matchesAbiToken(lowerFileName: String, abi: String): Boolean {
        if (abi == "x86") {
            return lowerFileName.contains("x86") && !lowerFileName.contains("x86_64")
        }
        return lowerFileName.contains(abi)
    }

    internal fun defaultSupportedAbis(): List<String> {
        return Build.SUPPORTED_ABIS?.toList().orEmpty()
    }

    fun formatReleaseDate(isoTimestamp: String): String {
        if (isoTimestamp.isBlank()) return ""
        return runCatching {
            val instant = Instant.parse(isoTimestamp.trim())
            val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        }.getOrDefault(isoTimestamp.substringBefore('T'))
    }
}

/**
 * Verifies integrity & compatibility metadata of downloaded APK archives (package name,
 * versionCode anti-rollback, and signing certificate SHA-256 digest match).
 */
object AppUpdateApkVerifier {
    data class VerificationResult(
        val valid: Boolean,
        val reason: String? = null,
    )

    fun verifyPackageMetadata(
        expectedPackageName: String,
        installedVersionCode: Long,
        installedSignerSha256: Set<String>,
        archivePackageName: String?,
        archiveVersionCode: Long,
        archiveSignerSha256: Set<String>,
    ): VerificationResult {
        if (archivePackageName != expectedPackageName) {
            return VerificationResult(false, "Package ID mismatch")
        }
        if (archiveVersionCode <= installedVersionCode) {
            return VerificationResult(false, "Version code rollback rejected")
        }
        if (installedSignerSha256.isEmpty() || archiveSignerSha256.isEmpty()) {
            return VerificationResult(false, "Missing signing certificate")
        }
        val matchesSigner = installedSignerSha256.any { it in archiveSignerSha256 }
        if (!matchesSigner) {
            return VerificationResult(false, "Signing certificate mismatch")
        }
        return VerificationResult(true)
    }

    @Suppress("DEPRECATION")
    fun verifyApkFileAgainstInstalled(context: Context, apkPath: String): VerificationResult {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        val installedInfo = runCatching {
            pm.getPackageInfo(context.packageName, flags)
        }.getOrNull() ?: return VerificationResult(false, "Cannot inspect installed package")

        val archiveInfo = runCatching {
            pm.getPackageArchiveInfo(apkPath, flags)
        }.getOrNull() ?: return VerificationResult(false, "Invalid APK archive")

        val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installedInfo.longVersionCode
        } else {
            installedInfo.versionCode.toLong()
        }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            archiveInfo.versionCode.toLong()
        }

        return verifyPackageMetadata(
            expectedPackageName = context.packageName,
            installedVersionCode = installedVersionCode,
            installedSignerSha256 = extractSignerSha256Set(installedInfo),
            archivePackageName = archiveInfo.packageName,
            archiveVersionCode = archiveVersionCode,
            archiveSignerSha256 = extractSignerSha256Set(archiveInfo),
        )
    }

    @Suppress("DEPRECATION")
    private fun extractSignerSha256Set(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()

        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

class AppUpdateChecker(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val endpointUrl: String = AppConfig.APP_LATEST_RELEASE_API_URL,
) {
    suspend fun checkLatestRelease(
        installedVersion: String = BuildConfig.VERSION_NAME,
        supportedAbis: List<String> = GitHubReleaseParser.defaultSupportedAbis(),
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "McQueenVPN/$installedVersion")
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: UnknownHostException) {
            return@withContext AppUpdateCheckResult.NoInternet
        } catch (e: ConnectException) {
            return@withContext AppUpdateCheckResult.NoInternet
        } catch (e: SocketTimeoutException) {
            return@withContext AppUpdateCheckResult.NoInternet
        } catch (e: IOException) {
            val msg = e.message.orEmpty().lowercase(Locale.US)
            return@withContext if (
                msg.contains("unable to resolve") ||
                msg.contains("network") ||
                msg.contains("failed to connect") ||
                msg.contains("timeout")
            ) {
                AppUpdateCheckResult.NoInternet
            } else {
                AppUpdateCheckResult.InvalidResponse(e.message ?: "Network error")
            }
        }

        response.use { res ->
            val remainingHeader = res.header("X-RateLimit-Remaining")?.toIntOrNull()
            val resetHeader = res.header("X-RateLimit-Reset")?.toLongOrNull()
            val bodyString = res.body?.string().orEmpty()

            if (res.code == 429 || (res.code == 403 && (remainingHeader == 0 || bodyString.contains("rate limit", ignoreCase = true)))) {
                return@withContext AppUpdateCheckResult.RateLimited(resetHeader)
            }

            if (!res.isSuccessful) {
                return@withContext AppUpdateCheckResult.InvalidResponse("HTTP ${res.code}")
            }

            evaluateReleaseJson(
                json = bodyString,
                installedVersion = installedVersion,
                supportedAbis = supportedAbis,
            )
        }
    }

    internal fun evaluateReleaseJson(
        json: String,
        installedVersion: String,
        supportedAbis: List<String> = GitHubReleaseParser.defaultSupportedAbis(),
    ): AppUpdateCheckResult {
        val release = GitHubReleaseParser.parseLatestRelease(json)
            ?: return AppUpdateCheckResult.InvalidResponse("Malformed release payload")

        val isNewer = SemanticVersion.isNewer(installedVersion, release.tagName)
            ?: return AppUpdateCheckResult.InvalidResponse("Invalid version tag: ${release.tagName}")

        if (!isNewer) {
            return AppUpdateCheckResult.UpToDate(
                installedVersion = installedVersion,
                latestVersion = release.tagName,
            )
        }

        val apkAsset = GitHubReleaseParser.selectBestApkAsset(release.assets, supportedAbis)
            ?: return AppUpdateCheckResult.MissingApkAsset(release.tagName)

        return AppUpdateCheckResult.UpdateAvailable(
            installedVersion = installedVersion,
            latestVersion = release.tagName,
            releaseNotes = release.releaseNotes,
            publishedAtFormatted = GitHubReleaseParser.formatReleaseDate(release.publishedAtRaw),
            apkAssetName = apkAsset.name,
            apkDownloadUrl = apkAsset.downloadUrl,
            apkSizeBytes = apkAsset.sizeBytes,
            releaseHtmlUrl = release.htmlUrl.ifBlank { "${AppConfig.APP_URL}/releases" },
        )
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
