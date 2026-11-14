package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.AetherKeyKind
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

data class AetherIdentity(
    val deviceId: String,
    val ipv4: String,
    val ipv6: String,
)

data class AetherIdentityStatus(
    val protocol: AetherProtocol,
    val primary: AetherIdentity?,
    val secondary: AetherIdentity? = null,
)

/** A key file of the identity folder, one of [AetherIdentityManager.KEY_FILES], with the identity it holds; null without one. */
data class AetherKey(
    val file: String,
    val identity: AetherIdentity?,
)

object AetherIdentityManager {

    const val BASE_FILE = "aether.toml"
    const val MASQUE_FILE = "aether-masque.toml"
    const val MASQUE_INNER_FILE = "aether-masque-secondary.toml"
    const val WIREGUARD_FILE = "aether-wg.toml"
    const val WIREGUARD_INNER_FILE = "aether-wg-secondary.toml"

    /**
     * The WireGuard key of WireGuard over MASQUE, beside [MASQUE_FILE] as the core names it. --register gool writes it
     * as it writes the other keys; a scan or a connection without it has the core register one through the MASQUE
     * tunnel, from inside WARP.
     */
    const val MASQUE_GOOL_FILE = "aether-masque-gool.toml"

    /** The key files of every kind, in the order the core registers them. */
    internal val KEY_FILES = listOf(WIREGUARD_FILE, WIREGUARD_INNER_FILE, MASQUE_FILE, MASQUE_INNER_FILE, MASQUE_GOOL_FILE)

    private const val WORK_DIR = "aether"

    /**
     * Where a renewal gathers its new keys. The keys in use stay in [WORK_DIR], untouched, until
     * every new key it registers is here; only then does each new key take the place of the old one.
     */
    private const val RENEWAL_DIR = "aether-renewal"

    /**
     * Written into [RENEWAL_DIR] once every new key of a renewal is there. From then on the new keys
     * are the keys: whatever stops the moving of them, [settle] finishes it.
     */
    internal const val READY_MARK = "ready"

    /**
     * Where the renewal before [RENEWAL_DIR] moved the keys in use while it registered new ones.
     * An app killed during such a renewal left it behind, holding keys nothing uses any more;
     * [settleIn] removes it the first time the app uses Aether.
     */
    private const val PREVIOUS_DIR = "aether-previous"

    /**
     * Five registrations, two MASQUE key enrollments and five WARP enablings at most, each of which the core may retry:
     * a key is saved only once WARP is enabled on it.
     */
    private const val RENEW_TIMEOUT_MS = 4 * 60_000L

    /**
     * A renewal through Tor or Psiphon, which a command written by hand may ask for, waits for the
     * carrier to come up first, which takes what a scan may take.
     */
    private const val RENEW_THROUGH_CARRIER_TIMEOUT_MS = 10 * 60_000L

    private const val SOURCE = "aether-key"

    private val identityField = Regex("""^(device_id|access_token|ipv4|ipv6|wg_private_key|cert_pem)\s*=\s*(?:"([^"]*)"|'([^']*)')(?:\s*#.*)?$""")
    private val multilineKeyStart = Regex("""^([A-Za-z0-9_]+)\s*=\s*(\"\"\"|''')(.*)$""")
    private val multilineDelimiter = Regex("\"\"\"|'''")
    private const val PEM_BEGIN_MARKER = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END_MARKER = "-----END CERTIFICATE-----"

    /** The core's last word in a registration, once every key it was asked for is saved. */
    private val keysRegistered = Regex("""identities ready: \S""")

    /** One renewal at a time: they share [RENEWAL_DIR]. */
    private val renewal = Mutex()

    fun workDir(context: Context): File = File(context.filesDir, WORK_DIR)

    private fun renewalDir(context: Context): File = File(context.filesDir, RENEWAL_DIR)

    suspend fun status(context: Context, protocol: AetherProtocol): AetherIdentityStatus =
        withContext(Dispatchers.IO) {
            settle(context)
            status(workDir(context), protocol)
        }

    /** Every key of the identity folder, each of [KEY_FILES] with the identity it holds. */
    suspend fun keys(context: Context): List<AetherKey> =
        withContext(Dispatchers.IO) {
            settle(context)
            keys(workDir(context), KEY_FILES)
        }

    /**
     * Registers new keys of [kind] by running the core on [arguments], which register them, see
     * [AetherKeys], and puts them in place of the keys in use once all of them are there. Until then
     * the keys in use stay where they are, untouched, so a failure, a cancellation or the app being
     * killed leaves them in use; the keys of another kind are never touched. When
     * [preserveValidExisting] is true, any file in [files] that is already valid in permanent
     * storage is preserved without overwriting. The core dials out through the exit-node [exit].
     * Returns the new keys, or null when the keys in use stay.
     */
    suspend fun renew(
        context: Context,
        kind: AetherKeyKind,
        arguments: List<String>,
        exit: AetherExit,
        onOutput: (String) -> Unit,
        preserveValidExisting: Boolean = false,
    ): List<AetherKey>? = renewal.withLock {
        val workDir = workDir(context)
        val renewalDir = renewalDir(context)
        val files = filesOf(kind)
        if (!renew(workDir, renewalDir, files, preserveValidExisting = preserveValidExisting) {
                register(context, arguments, exit, renewalDir, onOutput)
            }
        ) {
            return@withLock null
        }
        withContext(Dispatchers.IO) {
            if (!settle(workDir, renewalDir)) {
                LogUtil.w(AppConfig.TAG, "AetherIdentity: the new keys are ready but not all in place yet; the next core start moves the rest")
            }
            keys(workDir, files)
        }
    }

    /** [settleIn] the app's files folder; whatever reads the keys calls it first. */
    fun settle(context: Context): Boolean = settleIn(context.filesDir)

    /**
     * Removes [PREVIOUS_DIR] from [filesDir], the app's files folder, with the keys an interrupted
     * renewal of an older version left there, and then does what [settle] does on the identity
     * folder and the renewal folder in it; the keys in use stay as they are. A folder that cannot
     * all go now is tried again at the next use.
     */
    internal fun settleIn(filesDir: File): Boolean {
        if (!File(filesDir, PREVIOUS_DIR).deleteRecursively()) {
            LogUtil.w(AppConfig.TAG, "AetherIdentity: the keys an interrupted renewal of an older version left could not all be removed; the next use tries again")
        }
        return settle(File(filesDir, WORK_DIR), File(filesDir, RENEWAL_DIR))
    }

    /**
     * Runs the core once on [arguments] to register the keys they ask for into [renewalDir]: it
     * registers them, through a carrier if the arguments name one, and ends without scanning or
     * opening a tunnel. The run is over at its word that the keys are saved, which it writes at the
     * info level.
     */
    private suspend fun register(
        context: Context,
        arguments: List<String>,
        exit: AetherExit,
        renewalDir: File,
        onOutput: (String) -> Unit,
    ): Boolean = AetherCoreManager.runUntil(
        context = context,
        arguments = AetherCoreManager.withInfoLines(arguments),
        exit = exit,
        timeoutMs = if (AetherCoreManager.reachesWarpThroughCarrier(arguments)) RENEW_THROUGH_CARRIER_TIMEOUT_MS else RENEW_TIMEOUT_MS,
        source = SOURCE,
        onOutput = onOutput,
        keysDir = renewalDir,
    ) { line -> line.takeIf(::isRegistered) } != null

    internal fun status(workDir: File, protocol: AetherProtocol): AetherIdentityStatus = when (protocol) {
        AetherProtocol.MASQUE -> AetherIdentityStatus(protocol, read(File(workDir, MASQUE_FILE)))
        AetherProtocol.WIREGUARD -> AetherIdentityStatus(protocol, read(File(workDir, WIREGUARD_FILE)))
        AetherProtocol.GOOL -> AetherIdentityStatus(
            protocol,
            read(File(workDir, WIREGUARD_FILE)),
            read(File(workDir, WIREGUARD_INNER_FILE)),
        )

        AetherProtocol.MIM -> AetherIdentityStatus(
            protocol,
            read(File(workDir, MASQUE_FILE)),
            read(File(workDir, MASQUE_INNER_FILE)),
        )

        AetherProtocol.WG_OVER_MASQUE -> AetherIdentityStatus(
            protocol,
            read(File(workDir, MASQUE_FILE)),
            read(File(workDir, MASQUE_GOOL_FILE)),
        )
    }

    /** The key files a registration of [kind] writes, in the order of [KEY_FILES]. */
    fun filesOf(kind: AetherKeyKind): List<String> = when (kind) {
        AetherKeyKind.ALL -> KEY_FILES
        AetherKeyKind.WIREGUARD -> listOf(WIREGUARD_FILE)
        AetherKeyKind.MASQUE -> listOf(MASQUE_FILE)
        AetherKeyKind.GOOL -> listOf(WIREGUARD_FILE, WIREGUARD_INNER_FILE)
        AetherKeyKind.MIM -> listOf(MASQUE_FILE, MASQUE_INNER_FILE)
        AetherKeyKind.WG_OVER_MASQUE -> listOf(MASQUE_FILE, MASQUE_GOOL_FILE)
    }

    /** The key files a tunnel of [protocol] uses: its own, and the inner hop's for a two-hop one. */
    fun filesOf(protocol: AetherProtocol): List<String> = when (protocol) {
        AetherProtocol.MASQUE -> listOf(MASQUE_FILE)
        AetherProtocol.WIREGUARD -> listOf(WIREGUARD_FILE)
        AetherProtocol.GOOL -> listOf(WIREGUARD_FILE, WIREGUARD_INNER_FILE)
        AetherProtocol.MIM -> listOf(MASQUE_FILE, MASQUE_INNER_FILE)
        AetherProtocol.WG_OVER_MASQUE -> listOf(MASQUE_FILE, MASQUE_GOOL_FILE)
    }

    /**
     * The key files a core on [arguments] needs, which the WARP keys page gets: those of the protocol they run, read
     * as the core reads it, and none when they run Psiphon or Tor alone, with no WARP tunnel.
     */
    fun filesNeededBy(arguments: List<String>): List<String> =
        if (AetherCoreManager.psiphonModeOf(arguments) == AetherPsiphon.ONLY || AetherCoreManager.torModeOf(arguments) == AetherTor.ONLY) {
            emptyList()
        } else {
            filesOf(AetherCoreManager.protocolOf(arguments))
        }

    /** Which of [files] the identity folder lacks: not there, or not readable as a key. */
    suspend fun missing(context: Context, files: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            settle(context)
            missing(workDir(context), files)
        }

    internal fun missing(dir: File, files: List<String>): List<String> = files.filter { read(File(dir, it)) == null }

    /** Each of [files] in [dir] with the identity it holds. */
    internal fun keys(dir: File, files: List<String>): List<AetherKey> = files.map { AetherKey(it, read(File(dir, it))) }

    /**
     * Parses a persisted Aether identity TOML file and verifies that both Cloudflare device
     * registration (`device_id`, `access_token`) AND required enrollment / WARP activation
     * (`ipv4`/`ipv6`, plus `cert_pem` for MASQUE or `wg_private_key` for WireGuard) succeeded.
     *
     * Note: Aether v2.4.0 persists device registration credentials early before enrollment and
     * WARP activation complete; partial files containing only `device_id` (or missing `cert_pem` /
     * `wg_private_key` / `access_token` / tunnel IP) must therefore be rejected as incomplete.
     */
    internal fun parse(text: String, fileName: String? = null): AetherIdentity? {
        val fields = mutableMapOf<String, String>()
        var activeMultilineDelim: String? = null
        var activeMultilineKey: String? = null
        val multilineBuffer = StringBuilder()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (activeMultilineDelim != null) {
                val endIndex = line.indexOf(activeMultilineDelim)
                if (endIndex >= 0) {
                    if (activeMultilineKey == "cert_pem") {
                        val prefix = line.substring(0, endIndex)
                        if (prefix.isNotEmpty()) {
                            if (multilineBuffer.isNotEmpty()) multilineBuffer.append('\n')
                            multilineBuffer.append(prefix)
                        }
                        fields.putIfAbsent("cert_pem", multilineBuffer.toString())
                    }
                    activeMultilineDelim = null
                    activeMultilineKey = null
                    multilineBuffer.clear()
                } else if (activeMultilineKey == "cert_pem") {
                    if (multilineBuffer.isNotEmpty()) multilineBuffer.append('\n')
                    multilineBuffer.append(line)
                }
                continue
            }

            val mlMatch = multilineKeyStart.matchEntire(line)
            if (mlMatch != null) {
                val (key, delim, rest) = mlMatch.destructured
                val closeIndex = rest.indexOf(delim)
                if (closeIndex >= 0) {
                    if (key == "cert_pem") {
                        fields.putIfAbsent("cert_pem", rest.substring(0, closeIndex))
                    }
                } else {
                    activeMultilineDelim = delim
                    activeMultilineKey = key
                    multilineBuffer.clear()
                    if (key == "cert_pem" && rest.isNotEmpty()) {
                        multilineBuffer.append(rest)
                    }
                }
                continue
            }

            identityField.matchEntire(line)?.let { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
                fields.putIfAbsent(key, value)
            } ?: run {
                if (multilineDelimiter.findAll(line).count() % 2 == 1) {
                    activeMultilineDelim = multilineDelimiter.find(line)?.value
                    activeMultilineKey = null
                }
            }
        }

        val deviceId = fields["device_id"]?.takeIf { it.isNotBlank() } ?: return null
        val accessToken = fields["access_token"]?.takeIf { it.isNotBlank() } ?: return null
        val ipv4 = fields["ipv4"].orEmpty().trim()
        val ipv6 = fields["ipv6"].orEmpty().trim()
        if (ipv4.isBlank() && ipv6.isBlank()) return null

        val wgPrivateKey = fields["wg_private_key"].orEmpty().trim()
        val certPem = fields["cert_pem"].orEmpty()
        val enrolled = when (fileName) {
            MASQUE_FILE, MASQUE_INNER_FILE -> hasValidCertificatePem(certPem)
            WIREGUARD_FILE, WIREGUARD_INNER_FILE, MASQUE_GOOL_FILE -> wgPrivateKey.isNotBlank()
            else -> hasValidCertificatePem(certPem) || wgPrivateKey.isNotBlank()
        }
        if (!enrolled || accessToken.isBlank()) return null

        return AetherIdentity(deviceId, ipv4, ipv6)
    }

    private fun hasValidCertificatePem(pem: String?): Boolean {
        if (pem.isNullOrBlank()) return false
        val begin = pem.indexOf(PEM_BEGIN_MARKER)
        val end = pem.indexOf(PEM_END_MARKER)
        if (begin < 0 || end <= begin) return false
        val body = pem.substring(begin + PEM_BEGIN_MARKER.length, end)
        return body.isNotBlank()
    }

    /**
     * The tunnels over MASQUE share the MASQUE key and the others the WireGuard key; a two-hop tunnel adds a second key, of its
     * kind or, for WireGuard over MASQUE, a WireGuard key of its own, which no other tunnel uses.
     */
    fun sharesIdentity(first: AetherProtocol, second: AetherProtocol): Boolean =
        first.overMasque == second.overMasque

    internal fun isRegistered(line: String): Boolean = keysRegistered.containsMatchIn(line)

    /**
     * Gathers new keys in [renewalDir] through [register] and moves them over the keys in [workDir]
     * once every one of [files] is there and reads as a key. Nothing in [workDir] is touched before
     * that moment, so whatever stops a renewal earlier leaves the keys in use as they were; a stop
     * after it is finished by [settle]. A key not among [files] stays as it is. When
     * [preserveValidExisting] is true, any file among [files] that already reads as a valid key in
     * [workDir] is not overwritten during settlement. True when the new keys took the place of the
     * missing/renewed ones. The steps on the files run on [io].
     */
    internal suspend fun renew(
        workDir: File,
        renewalDir: File,
        files: List<String>,
        io: CoroutineDispatcher = Dispatchers.IO,
        preserveValidExisting: Boolean = false,
        register: suspend () -> Boolean,
    ): Boolean {
        val cleared = withContext(io) {
            // A renewal whose keys were all ready is finished first; what one stopped earlier left is no key anyone uses.
            settle(workDir, renewalDir) && renewalDir.deleteRecursively() && renewalDir.mkdirs()
        }
        if (!cleared) return false
        // A cancellation can be delivered as the result of a blocking step comes back, after the step
        // itself has run; whether the new keys were marked ready is therefore recorded inside that
        // step, and the finally block goes by the record rather than by a value the step returned.
        val ready = AtomicBoolean(false)
        try {
            if (register()) {
                withContext(io) {
                    // A key the run left beside the ones asked for goes with the folder rather than into place.
                    (KEY_FILES - files.toSet()).forEach { File(renewalDir, it).delete() }
                    val complete = if (preserveValidExisting) {
                        files.all { name ->
                            read(File(renewalDir, name)) != null || read(File(workDir, name)) != null
                        }.also { allSatisfied ->
                            if (allSatisfied) {
                                // Never overwrite a valid existing identity file in permanent storage.
                                files.forEach { name ->
                                    if (read(File(workDir, name)) != null) {
                                        File(renewalDir, name).delete()
                                    }
                                }
                            }
                        }
                    } else {
                        isComplete(renewalDir, files)
                    }
                    ready.set(complete && markReady(renewalDir))
                }
            }
        } finally {
            withContext(NonCancellable + io) {
                if (ready.get()) settle(workDir, renewalDir) else renewalDir.deleteRecursively()
            }
        }
        return ready.get()
    }

    /**
     * Moves the new keys of a renewal whose keys were all ready over the keys in [workDir], one
     * after another, and then removes [renewalDir]. Without the ready mark there is nothing to move:
     * the new keys are incomplete, or a renewal is still gathering them, and the keys in use stay.
     * Another process may be finishing the same renewal at the same time; a key it moved first is
     * simply gone here. True when no renewal with ready keys waits any longer.
     */
    internal fun settle(workDir: File, renewalDir: File): Boolean {
        if (!File(renewalDir, READY_MARK).isFile) return true
        workDir.mkdirs()
        for (name in KEY_FILES) {
            val renewed = File(renewalDir, name)
            if (renewed.isFile && !moveOver(renewed, File(workDir, name))) return false
        }
        // The mark goes with the folder, after every key: until then it tells that keys are left to move.
        return renewalDir.deleteRecursively()
    }

    /**
     * Moves [source] over [target] in one step that replaces the key in use, or leaves it as it
     * was; true once [source] is gone, moved by this call or by another process finishing the same
     * renewal.
     */
    private fun moveOver(source: File, target: File): Boolean = try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: IOException) {
        !source.isFile
    }

    /** Whether every one of [files] is in [dir] and reads as a key. */
    internal fun isComplete(dir: File, files: List<String>): Boolean = files.all { read(File(dir, it)) != null }

    private fun markReady(dir: File): Boolean = try {
        File(dir, READY_MARK).createNewFile()
    } catch (_: IOException) {
        false
    }

    private fun read(file: File): AetherIdentity? = try {
        if (file.isFile) parse(file.readText(), file.name) else null
    } catch (_: IOException) {
        null
    }
}
