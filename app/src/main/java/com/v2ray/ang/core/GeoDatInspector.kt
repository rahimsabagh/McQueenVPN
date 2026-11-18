package com.v2ray.ang.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight streaming protobuf inspector for Xray/v2ray geoip.dat and geosite.dat archives.
 *
 * Verifies that required entries (specifically country code "IR" IPv4/IPv6 CIDRs in geoip.dat
 * and "CATEGORY-IR" domains in geosite.dat) are genuinely present without loading multi-megabyte
 * protobuf blobs into heap memory. Results are cached by (path, length, lastModified).
 */
internal object GeoDatInspector {

    data class GeoIpEntryStats(
        val fileExists: Boolean,
        val validArchive: Boolean,
        val hasEntry: Boolean,
        val ipv4CidrCount: Int,
        val ipv6CidrCount: Int,
    ) {
        val hasAnyCidrs: Boolean get() = hasEntry && (ipv4CidrCount + ipv6CidrCount) > 0
    }

    data class GeoSiteEntryStats(
        val fileExists: Boolean,
        val validArchive: Boolean,
        val hasEntry: Boolean,
        val domainCount: Int,
    ) {
        val hasDomains: Boolean get() = hasEntry && domainCount > 0
    }

    private data class CacheKey(
        val path: String,
        val length: Long,
        val lastModified: Long,
        val targetCode: String,
    )

    private val geoIpCache = ConcurrentHashMap<CacheKey, GeoIpEntryStats>()
    private val geoSiteCache = ConcurrentHashMap<CacheKey, GeoSiteEntryStats>()

    fun inspectGeoIp(file: File, countryCode: String = "IR"): GeoIpEntryStats {
        if (!file.isFile || file.length() <= 0L) {
            return GeoIpEntryStats(
                fileExists = file.isFile,
                validArchive = false,
                hasEntry = false,
                ipv4CidrCount = 0,
                ipv6CidrCount = 0,
            )
        }
        val normalizedTarget = countryCode.trim().uppercase()
        val key = CacheKey(file.absolutePath, file.length(), file.lastModified(), normalizedTarget)
        geoIpCache[key]?.let { return it }

        val stats = runCatching {
            file.inputStream().buffered(64 * 1024).use { stream ->
                scanGeoIpStream(stream, normalizedTarget)
            }
        }.getOrElse {
            GeoIpEntryStats(
                fileExists = true,
                validArchive = false,
                hasEntry = false,
                ipv4CidrCount = 0,
                ipv6CidrCount = 0,
            )
        }
        geoIpCache[key] = stats
        return stats
    }

    fun inspectGeoSite(file: File, listCode: String = "CATEGORY-IR"): GeoSiteEntryStats {
        if (!file.isFile || file.length() <= 0L) {
            return GeoSiteEntryStats(
                fileExists = file.isFile,
                validArchive = false,
                hasEntry = false,
                domainCount = 0,
            )
        }
        val normalizedTarget = listCode.trim().uppercase()
        val key = CacheKey(file.absolutePath, file.length(), file.lastModified(), normalizedTarget)
        geoSiteCache[key]?.let { return it }

        val stats = runCatching {
            file.inputStream().buffered(64 * 1024).use { stream ->
                scanGeoSiteStream(stream, normalizedTarget)
            }
        }.getOrElse {
            GeoSiteEntryStats(
                fileExists = true,
                validArchive = false,
                hasEntry = false,
                domainCount = 0,
            )
        }
        geoSiteCache[key] = stats
        return stats
    }

    private fun scanGeoIpStream(stream: BufferedInputStream, targetCode: String): GeoIpEntryStats {
        var entryCount = 0
        var hasEntry = false
        var ipv4Count = 0
        var ipv6Count = 0

        while (true) {
            val tag = readVarintOrNull(stream) ?: break
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED) {
                val msgLen = readVarint(stream).toInt()
                require(msgLen >= 0) { "Negative protobuf message length" }
                entryCount++
                val bounded = BoundedInputStream(stream, msgLen)
                var codeMatched = false
                var msgIpv4 = 0
                var msgIpv6 = 0

                while (bounded.remaining > 0) {
                    val innerTag = readVarintOrNull(bounded) ?: break
                    val innerField = (innerTag ushr 3).toInt()
                    val innerWire = (innerTag and 0x07).toInt()
                    when {
                        innerField == 1 && innerWire == WIRE_LENGTH_DELIMITED -> {
                            val codeLen = readVarint(bounded).toInt()
                            val code = readUtf8Exact(bounded, codeLen).uppercase()
                            codeMatched = (code == targetCode)
                        }

                        innerField == 2 && innerWire == WIRE_LENGTH_DELIMITED -> {
                            val cidrLen = readVarint(bounded).toInt()
                            if (codeMatched) {
                                val cidrBounded = BoundedInputStream(bounded, cidrLen)
                                val ipByteLen = readCidrIpLength(cidrBounded)
                                when (ipByteLen) {
                                    4 -> msgIpv4++
                                    16 -> msgIpv6++
                                }
                                cidrBounded.skipRemaining()
                            } else {
                                skipExactly(bounded, cidrLen.toLong())
                            }
                        }

                        else -> skipField(bounded, innerWire)
                    }
                }
                bounded.skipRemaining()
                if (codeMatched) {
                    hasEntry = true
                    ipv4Count += msgIpv4
                    ipv6Count += msgIpv6
                    if (ipv4Count + ipv6Count > 0) {
                        break
                    }
                }
            } else {
                skipField(stream, wireType)
            }
        }

        return GeoIpEntryStats(
            fileExists = true,
            validArchive = entryCount > 0,
            hasEntry = hasEntry,
            ipv4CidrCount = ipv4Count,
            ipv6CidrCount = ipv6Count,
        )
    }

    private fun readCidrIpLength(stream: BoundedInputStream): Int {
        var ipLength = -1
        while (stream.remaining > 0) {
            val tag = readVarintOrNull(stream) ?: break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x07).toInt()
            if (field == 1 && wire == WIRE_LENGTH_DELIMITED) {
                val len = readVarint(stream).toInt()
                skipExactly(stream, len.toLong())
                ipLength = len
            } else {
                skipField(stream, wire)
            }
        }
        return ipLength
    }

    private fun scanGeoSiteStream(stream: BufferedInputStream, targetCode: String): GeoSiteEntryStats {
        var entryCount = 0
        var hasEntry = false
        var domainCount = 0

        while (true) {
            val tag = readVarintOrNull(stream) ?: break
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED) {
                val msgLen = readVarint(stream).toInt()
                require(msgLen >= 0) { "Negative protobuf message length" }
                entryCount++
                val bounded = BoundedInputStream(stream, msgLen)
                var codeMatched = false
                var msgDomains = 0

                while (bounded.remaining > 0) {
                    val innerTag = readVarintOrNull(bounded) ?: break
                    val innerField = (innerTag ushr 3).toInt()
                    val innerWire = (innerTag and 0x07).toInt()
                    when {
                        innerField == 1 && innerWire == WIRE_LENGTH_DELIMITED -> {
                            val codeLen = readVarint(bounded).toInt()
                            val code = readUtf8Exact(bounded, codeLen).uppercase()
                            codeMatched = (code == targetCode)
                        }

                        innerField == 2 && innerWire == WIRE_LENGTH_DELIMITED -> {
                            val domLen = readVarint(bounded).toInt()
                            skipExactly(bounded, domLen.toLong())
                            if (codeMatched) {
                                msgDomains++
                            }
                        }

                        else -> skipField(bounded, innerWire)
                    }
                }
                bounded.skipRemaining()
                if (codeMatched) {
                    hasEntry = true
                    domainCount += msgDomains
                    if (domainCount > 0) {
                        break
                    }
                }
            } else {
                skipField(stream, wireType)
            }
        }

        return GeoSiteEntryStats(
            fileExists = true,
            validArchive = entryCount > 0,
            hasEntry = hasEntry,
            domainCount = domainCount,
        )
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_32BIT = 5

    private fun skipField(stream: InputStream, wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint(stream)
            WIRE_64BIT -> skipExactly(stream, 8L)
            WIRE_LENGTH_DELIMITED -> {
                val len = readVarint(stream)
                require(len >= 0L) { "Negative length-delimited field" }
                skipExactly(stream, len)
            }
            WIRE_32BIT -> skipExactly(stream, 4L)
            else -> error("Unsupported protobuf wire type: $wireType")
        }
    }

    private fun readVarintOrNull(stream: InputStream): Long? {
        val first = stream.read()
        if (first < 0) return null
        if ((first and 0x80) == 0) return first.toLong()
        var result = (first and 0x7F).toLong()
        var shift = 7
        while (shift < 64) {
            val b = stream.read()
            if (b < 0) throw EOFException("Truncated varint")
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        error("Malformed varint")
    }

    private fun readVarint(stream: InputStream): Long {
        return readVarintOrNull(stream) ?: throw EOFException("Unexpected EOF reading varint")
    }

    private fun readUtf8Exact(stream: InputStream, len: Int): String {
        require(len in 0..4096) { "Unexpected string length in geo header: $len" }
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val read = stream.read(buf, offset, len - offset)
            if (read < 0) throw EOFException("Unexpected EOF reading string")
            offset += read
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun skipExactly(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val b = stream.read()
                if (b < 0) throw EOFException("Unexpected EOF skipping bytes")
                remaining--
            }
        }
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        limit: Int,
    ) : InputStream() {
        var remaining: Int = limit
            private set

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = delegate.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len, remaining)
            val count = delegate.read(b, off, toRead)
            if (count > 0) remaining -= count
            return count
        }

        override fun skip(n: Long): Long {
            val toSkip = minOf(n, remaining.toLong())
            if (toSkip <= 0L) return 0L
            val skipped = delegate.skip(toSkip)
            if (skipped > 0) remaining -= skipped.toInt()
            return skipped
        }

        fun skipRemaining() {
            if (remaining > 0) {
                skipExactly(this, remaining.toLong())
            }
        }
    }
}
