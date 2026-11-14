package com.v2ray.ang.core

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherFingerprint
import com.v2ray.ang.enums.AetherKeyKind
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class ProvisionFailureKind(@param:StringRes val userMessageRes: Int) {
    IDENTITY_MISSING(R.string.mcqueen_error_identity_missing),
    REGISTRATION_API_UNREACHABLE(R.string.mcqueen_error_registration_api_unreachable),
    REGISTRATION_REJECTED(R.string.mcqueen_error_registration_rejected),
    IDENTITY_PERSISTENCE_FAILURE(R.string.mcqueen_error_identity_persistence_failure),
    PROCESS_STARTUP_FAILURE(R.string.mcqueen_error_aether_process_startup_failure),
}

enum class TunnelFailureKind(@param:StringRes val userMessageRes: Int) {
    IDENTITY_MISSING(R.string.mcqueen_error_identity_missing),
    REGISTRATION_API_UNREACHABLE(R.string.mcqueen_error_registration_api_unreachable),
    REGISTRATION_REJECTED(R.string.mcqueen_error_registration_rejected),
    IDENTITY_PERSISTENCE_FAILURE(R.string.mcqueen_error_identity_persistence_failure),
    PROCESS_STARTUP_FAILURE(R.string.mcqueen_error_aether_process_startup_failure),
    TUNNEL_HANDSHAKE_FAILURE(R.string.mcqueen_error_tunnel_handshake_failure),
}

sealed class AetherProvisionResult {
    data object NotRequired : AetherProvisionResult()
    data class AlreadyReady(val requiredFiles: List<String>) : AetherProvisionResult()
    data class Provisioned(val requiredFiles: List<String>) : AetherProvisionResult()
    data class Failed(
        val kind: ProvisionFailureKind,
        val diagnosticDetail: String? = null,
    ) : AetherProvisionResult()

    val isReady: Boolean
        get() = this is NotRequired || this is AlreadyReady || this is Provisioned
}

interface AetherIdentityBackend {
    fun isSupported(context: Context): Boolean
    fun settle(context: Context): Boolean
    fun missingReadable(context: Context, files: List<String>): List<String>
    suspend fun renew(
        context: Context,
        kind: AetherKeyKind,
        arguments: List<String>,
        exit: AetherExit,
        onOutput: (String) -> Unit,
    ): List<AetherKey>?
}

object RealAetherIdentityBackend : AetherIdentityBackend {
    override fun isSupported(context: Context): Boolean = AetherCoreManager.isSupported(context)

    override fun settle(context: Context): Boolean = AetherIdentityManager.settle(context)

    override fun missingReadable(context: Context, files: List<String>): List<String> {
        val workDir = AetherIdentityManager.workDir(context)
        val unparseable = AetherIdentityManager.missing(workDir, files).toSet()
        return files.filter { name ->
            val file = File(workDir, name)
            name in unparseable || !file.isFile || !file.canRead()
        }
    }

    override suspend fun renew(
        context: Context,
        kind: AetherKeyKind,
        arguments: List<String>,
        exit: AetherExit,
        onOutput: (String) -> Unit,
    ): List<AetherKey>? = AetherIdentityManager.renew(
        context = context,
        kind = kind,
        arguments = arguments,
        exit = exit,
        onOutput = onOutput,
        preserveValidExisting = true,
    )
}

/**
 * Ensures Aether WARP identity files required by a profile exist, are persisted in permanent
 * app-private storage, and are readable before launching probes or the VPN tunnel.
 *
 * Existing valid identities are always reused and never overwritten on failed renewals or
 * partial two-hop provisioning.
 */
class AetherIdentityProvisioner(
    private val backend: AetherIdentityBackend = RealAetherIdentityBackend,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val logger: (priority: Int, message: String) -> Unit = { priority, message ->
        when (priority) {
            Log.ERROR -> LogUtil.e(AppConfig.TAG, message)
            Log.WARN -> LogUtil.w(AppConfig.TAG, message)
            else -> LogUtil.i(AppConfig.TAG, message)
        }
    },
) {

    private val provisionMutex = Mutex()

    fun requiredFiles(profile: ProfileItem): List<String> =
        requiredFiles(AetherCore.of(profile))

    fun requiredFiles(core: AetherCore): List<String> =
        AetherIdentityManager.filesNeededBy(core.arguments)

    suspend fun hasValidIdentity(context: Context, profile: ProfileItem): Boolean =
        hasValidIdentity(context, AetherCore.of(profile))

    suspend fun hasValidIdentity(context: Context, core: AetherCore): Boolean = withContext(Dispatchers.IO) {
        val required = requiredFiles(core)
        if (required.isEmpty()) return@withContext true
        backend.settle(context)
        backend.missingReadable(context, required).isEmpty()
    }

    suspend fun ensureIdentity(context: Context, profile: ProfileItem): AetherProvisionResult =
        ensureIdentity(context, AetherCore.of(profile))

    suspend fun ensureIdentity(context: Context, core: AetherCore): AetherProvisionResult =
        withContext(Dispatchers.IO) {
            val required = requiredFiles(core)
            if (required.isEmpty()) {
                return@withContext AetherProvisionResult.NotRequired
            }

            provisionMutex.withLock {
                // Complete any ready renewal left by an earlier interrupted process without deleting valid keys.
                backend.settle(context)
                if (backend.missingReadable(context, required).isEmpty()) {
                    logger(Log.INFO, "AetherIdentityProvisioner: reusing existing valid identity files=${required.joinToString()}")
                    return@withLock AetherProvisionResult.AlreadyReady(required)
                }

                if (!backend.isSupported(context)) {
                    logger(Log.ERROR, "AetherIdentityProvisioner: libaether.so is missing or not executable")
                    return@withLock AetherProvisionResult.Failed(
                        kind = ProvisionFailureKind.PROCESS_STARTUP_FAILURE,
                        diagnosticDetail = "Aether core binary unavailable",
                    )
                }

                val keyKind = keyKindFor(core.protocol)
                val regArgs = registrationArguments(core, keyKind)
                val sanitizedOutput = java.util.Collections.synchronizedList(mutableListOf<String>())
                val startedAtMs = clockMs()

                logger(Log.INFO, "AetherIdentityProvisioner: registering missing identity kind=${keyKind.register}")

                val renewedKeys = try {
                    backend.renew(
                        context = context,
                        kind = keyKind,
                        arguments = regArgs,
                        exit = core.exit,
                    ) { rawLine ->
                        val clean = sanitizeDiagnosticLine(rawLine)
                        if (clean.isNotEmpty()) {
                            sanitizedOutput.add(clean)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    val cleanErr = sanitizeDiagnosticLine(t.message ?: t.javaClass.simpleName)
                    logger(Log.ERROR, "AetherIdentityProvisioner: registration threw exception: $cleanErr")
                    val snapshot = synchronized(sanitizedOutput) { sanitizedOutput.toList() }
                    val kind = classifyThrowable(t, snapshot)
                    return@withLock AetherProvisionResult.Failed(kind = kind, diagnosticDetail = cleanErr)
                }

                val elapsedMs = (clockMs() - startedAtMs).coerceAtLeast(0L)
                val outputSnapshot = synchronized(sanitizedOutput) { sanitizedOutput.toList() }

                if (renewedKeys == null) {
                    val failureKind = classifyRegistrationFailure(outputSnapshot, elapsedMs)
                    val detail = lastRelevantDiagnostic(outputSnapshot)
                    logger(
                        Log.WARN,
                        "AetherIdentityProvisioner: registration failed kind=$failureKind elapsedMs=$elapsedMs detail=${detail.orEmpty()}",
                    )
                    return@withLock AetherProvisionResult.Failed(
                        kind = failureKind,
                        diagnosticDetail = detail,
                    )
                }

                val settled = backend.settle(context)
                val stillMissing = backend.missingReadable(context, required)
                val allRequiredReturned = required.all { req ->
                    renewedKeys.any { it.file == req && it.identity != null }
                }

                if (!settled) {
                    logger(Log.ERROR, "AetherIdentityProvisioner: failed to settle renewed identity into permanent storage")
                    return@withLock AetherProvisionResult.Failed(
                        kind = ProvisionFailureKind.IDENTITY_PERSISTENCE_FAILURE,
                        diagnosticDetail = "Unable to commit identity files to permanent storage",
                    )
                }

                if (stillMissing.isNotEmpty() || !allRequiredReturned) {
                    val missingDetail = if (stillMissing.isNotEmpty()) stillMissing.joinToString() else required.joinToString()
                    logger(
                        Log.ERROR,
                        "AetherIdentityProvisioner: identity files incomplete or unreadable after registration: $missingDetail",
                    )
                    return@withLock AetherProvisionResult.Failed(
                        kind = ProvisionFailureKind.IDENTITY_MISSING,
                        diagnosticDetail = "Missing or incomplete identity files: $missingDetail",
                    )
                }

                logger(Log.INFO, "AetherIdentityProvisioner: provisioned and verified files=${required.joinToString()}")
                AetherProvisionResult.Provisioned(required)
            }
        }

    companion object {
        private val SECRET_KV_REGEX = Regex(
            """(?i)\b(device_id|wg_private_key|private_key|public_key|access_token|token|secret|password|pass|cert_pem|authorization)\b\s*[:=]\s*("[^"]*"|'[^']*'|\S+)"""
        )
        private val PEM_REGEX = Regex("""-----BEGIN [^-]+-----.*""")
        private val PROXY_URI_REGEX = Regex("""(?i)\b(vless|vmess|trojan|ss|ssr|hy2|hysteria2?|tuic|wireguard|masque|aether)://\S+""")
        private val IDENTITY_IP_KV_REGEX = Regex("""(?i)\b(ipv4|ipv6)\b\s*[:=]\s*("[^"]*"|'[^']*'|\S+)""")

        private val TLS_VALUE_FLAGS = listOf(
            "--tls-ciphers",
            "--tls13-ciphers",
            "--tls-groups",
            "--tls-key-shares",
            "--tls-sigalgs",
            "--tls-cert-compression",
            "--tls-alps",
            "--tls-trust-anchors",
            "--tls-server-padding",
        )
        private val TLS_BOOL_FLAGS = listOf(
            "--disable-grease",
            "--enable-grease-sigalgs",
            "--enable-ech-grease",
            "--disable-ocsp-stapling",
            "--disable-sct",
            "--disable-session-ticket",
            "--disable-extension-permutation",
        )

        fun keyKindFor(protocol: AetherProtocol): AetherKeyKind = when (protocol) {
            AetherProtocol.WIREGUARD -> AetherKeyKind.WIREGUARD
            AetherProtocol.MASQUE -> AetherKeyKind.MASQUE
            AetherProtocol.GOOL -> AetherKeyKind.GOOL
            AetherProtocol.MIM -> AetherKeyKind.MIM
            AetherProtocol.WG_OVER_MASQUE -> AetherKeyKind.WG_OVER_MASQUE
        }

        fun registrationArguments(core: AetherCore, kind: AetherKeyKind = keyKindFor(core.protocol)): List<String> {
            val coreArgs = core.arguments
            return buildList {
                addAll(listOf(AetherKeys.REGISTER, kind.register))

                // Preserve WARP API overrides from the profile/command if present.
                for (flag in listOf("--api-address", "--api-sni", "--api-host")) {
                    AetherCoreManager.valueAfter(coreArgs, flag)?.let { addAll(listOf(flag, it)) }
                }

                // Preserve TLS ClientHello fragmentation settings used for WARP API calls.
                if ("--fragment" in coreArgs) {
                    add("--fragment")
                    AetherCoreManager.valueAfter(coreArgs, "--fragment-size")?.let { addAll(listOf("--fragment-size", it)) }
                    AetherCoreManager.valueAfter(coreArgs, "--fragment-delay")?.let { addAll(listOf("--fragment-delay", it)) }
                }

                // Preserve ECH settings for WARP API requests.
                if ("--ech" in coreArgs) {
                    addAll(listOf("--ech", AetherCoreManager.valueAfter(coreArgs, "--ech") ?: "auto"))
                    AetherCoreManager.valueAfter(coreArgs, "--ech-dns")?.let { addAll(listOf("--ech-dns", it)) }
                    AetherCoreManager.valueAfter(coreArgs, "--ech-domain")?.let { addAll(listOf("--ech-domain", it)) }
                }

                // Forward profile TLS fingerprint flags when specified; otherwise apply Chrome defaults.
                val hasCustomTlsFlags = TLS_VALUE_FLAGS.any { it in coreArgs } || TLS_BOOL_FLAGS.any { it in coreArgs }
                if (hasCustomTlsFlags) {
                    for (flag in TLS_VALUE_FLAGS) {
                        AetherCoreManager.valueAfter(coreArgs, flag)?.let { addAll(listOf(flag, it)) }
                    }
                    for (flag in TLS_BOOL_FLAGS) {
                        if (flag in coreArgs) add(flag)
                    }
                } else {
                    addAll(AetherFingerprint.CHROME.arguments)
                }

                // Preserve full reverse carrier options needed to reach Cloudflare APIs from censored networks.
                if (AetherCoreManager.torModeOf(coreArgs) == AetherTor.REVERSE) {
                    add("--tor-reverse")
                    AetherCoreManager.valueAfter(coreArgs, AetherCoreManager.TOR_BIND)
                        ?.let { addAll(listOf(AetherCoreManager.TOR_BIND, it)) }
                    val bridgesIdx = coreArgs.lastIndexOf("--tor-bridges")
                    val noBridgesIdx = coreArgs.lastIndexOf("--no-tor-bridges")
                    when {
                        bridgesIdx > noBridgesIdx && bridgesIdx >= 0 -> add("--tor-bridges")
                        noBridgesIdx > bridgesIdx && noBridgesIdx >= 0 -> add("--no-tor-bridges")
                    }
                    coreArgs.forEachIndexed { index, token ->
                        if (token == "--tor-bridge") {
                            coreArgs.getOrNull(index + 1)?.let { addAll(listOf("--tor-bridge", it)) }
                        }
                    }
                    AetherCoreManager.valueAfter(coreArgs, "--tor-relays")?.let { addAll(listOf("--tor-relays", it)) }
                }

                if (AetherCoreManager.psiphonModeOf(coreArgs) == AetherPsiphon.REVERSE) {
                    add("--psiphon-reverse")
                    for (flag in listOf(
                        AetherCoreManager.PSIPHON_BIND,
                        "--psiphon-mode",
                        "--psiphon-cdn-ips",
                        "--psiphon-cdn-sni",
                        "--psiphon-cdn-sets",
                        "--psiphon-region",
                        AetherCoreManager.PSIPHON_SERVER_ENTRIES,
                    )) {
                        AetherCoreManager.valueAfter(coreArgs, flag)?.let { addAll(listOf(flag, it)) }
                    }
                }
            }
        }

        fun sanitizeDiagnosticLine(rawLine: String): String {
            var clean = rawLine.trim()
            if (clean.isEmpty()) return ""
            clean = PEM_REGEX.replace(clean, "[REDACTED_PEM]")
            clean = PROXY_URI_REGEX.replace(clean) { match -> "${match.groupValues[1]}://[REDACTED]" }
            clean = SECRET_KV_REGEX.replace(clean) { match -> "${match.groupValues[1]}=[REDACTED]" }
            clean = IDENTITY_IP_KV_REGEX.replace(clean) { match -> "${match.groupValues[1]}=[REDACTED]" }
            return if (clean.length > 220) clean.take(220) + "…" else clean
        }

        internal fun classifyRegistrationFailure(
            sanitizedLines: List<String>,
            elapsedMs: Long,
        ): ProvisionFailureKind {
            val joined = sanitizedLines.joinToString("\n").lowercase(Locale.ROOT)

            // Cloudflare API registered keys, yet atomic settle / write to permanent storage failed.
            if (sanitizedLines.any { AetherIdentityManager.isRegistered(it) }) {
                return ProvisionFailureKind.IDENTITY_PERSISTENCE_FAILURE
            }

            if (containsAny(
                    joined,
                    "identity refused",
                    "rejected",
                    "forbidden",
                    "unauthorized",
                    "too many requests",
                    "rate limit",
                    "http 400",
                    "http 401",
                    "http 403",
                    "http 429",
                    "status 400",
                    "status 401",
                    "status 403",
                    "status 429",
                    "403 forbidden",
                    "429 too many",
                )
            ) {
                return ProvisionFailureKind.REGISTRATION_REJECTED
            }

            if (containsAny(
                    joined,
                    "read-only file system",
                    "no space left",
                    "failed to write",
                    "cannot write",
                    "persist",
                    "aether-renewal",
                    "atomic_move",
                )
            ) {
                return ProvisionFailureKind.IDENTITY_PERSISTENCE_FAILURE
            }

            if (containsAny(
                    joined,
                    "failed to launch",
                    "exec format",
                    "no such file",
                    "enoent",
                    "cannot execute",
                    "permission denied",
                    "not executable",
                    "xray exit did not open",
                    "panic:",
                )
            ) {
                return ProvisionFailureKind.PROCESS_STARTUP_FAILURE
            }

            if (containsAny(
                    joined,
                    "api:",
                    "io:",
                    "tls:",
                    "ech:",
                    "quic:",
                    "h3:",
                    "timeout",
                    "timed out",
                    "connection refused",
                    "network is unreachable",
                    "failed to lookup",
                    "dns",
                    "connect error",
                    "broken pipe",
                    "connection reset",
                    "unexpected eof",
                    "unreachable",
                )
            ) {
                return ProvisionFailureKind.REGISTRATION_API_UNREACHABLE
            }

            return if (sanitizedLines.isEmpty() && elapsedMs < 5_000L) {
                ProvisionFailureKind.PROCESS_STARTUP_FAILURE
            } else {
                ProvisionFailureKind.REGISTRATION_API_UNREACHABLE
            }
        }

        fun classifyTunnelFailure(rawReason: String?): TunnelFailureKind {
            val lower = rawReason?.let(::sanitizeDiagnosticLine)?.lowercase(Locale.ROOT).orEmpty()
            return when {
                containsAny(lower, "missing", "identity", "no warp key") &&
                    !containsAny(lower, "handshake", "timed out", "traffic check") -> TunnelFailureKind.IDENTITY_MISSING

                containsAny(lower, "api unreachable", "registration api") -> TunnelFailureKind.REGISTRATION_API_UNREACHABLE
                containsAny(lower, "rejected", "refused", "forbidden", "429", "403") -> TunnelFailureKind.REGISTRATION_REJECTED
                containsAny(lower, "persist", "storage", "unreadable") -> TunnelFailureKind.IDENTITY_PERSISTENCE_FAILURE
                containsAny(lower, "binary", "launch", "startup", "not supported", "aether core exited", "stopped") ->
                    TunnelFailureKind.PROCESS_STARTUP_FAILURE

                else -> TunnelFailureKind.TUNNEL_HANDSHAKE_FAILURE
            }
        }

        private fun classifyThrowable(t: Throwable, outputLines: List<String>): ProvisionFailureKind {
            if (outputLines.isNotEmpty()) {
                return classifyRegistrationFailure(outputLines, elapsedMs = 0L)
            }
            val msg = t.message?.lowercase(Locale.ROOT).orEmpty()
            return when {
                containsAny(msg, "no space", "read-only", "persist", "atomic") ->
                    ProvisionFailureKind.IDENTITY_PERSISTENCE_FAILURE
                containsAny(msg, "timeout", "dns", "connect", "network", "unreachable") ->
                    ProvisionFailureKind.REGISTRATION_API_UNREACHABLE
                else -> ProvisionFailureKind.PROCESS_STARTUP_FAILURE
            }
        }

        private fun lastRelevantDiagnostic(lines: List<String>): String? {
            return lines.lastOrNull { line ->
                val lower = line.lowercase(Locale.ROOT)
                lower.contains("error") ||
                    lower.contains("api:") ||
                    lower.contains("io:") ||
                    lower.contains("tls:") ||
                    lower.contains("refused") ||
                    lower.contains("timeout")
            } ?: lines.lastOrNull()
        }

        private fun containsAny(haystack: String, vararg needles: String): Boolean =
            needles.any { haystack.contains(it) }
    }
}
