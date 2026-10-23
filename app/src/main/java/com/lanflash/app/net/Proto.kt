package com.lanflash.app.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * LAN transfer wire protocol v1.
 *
 * Frame = type(1 byte) + payloadLength(4 bytes, big endian) + payload
 */
object Proto {
    const val VERSION = 1

    const val T_HELLO = 1
    const val T_MANIFEST = 2
    const val T_DATA = 3
    const val T_DONE = 4
    const val T_COMPLETE = 5
    const val T_ERROR = 6

    const val CHUNK_SIZE = 4 * 1024 * 1024
    const val MAX_FRAME = 64 * 1024 * 1024
    const val DEFAULT_STREAMS = 4

    private val rnd = SecureRandom()

    fun randomToken(): String {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(type)
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
    }

    @Throws(IOException::class)
    fun readFrame(inp: DataInputStream): Frame {
        val type = inp.read()
        if (type < 0) throw IOException("connection closed")
        val len = (inp.readUnsignedByte() shl 24) or
            (inp.readUnsignedByte() shl 16) or
            (inp.readUnsignedByte() shl 8) or
            inp.readUnsignedByte()
        if (len < 0 || len > MAX_FRAME) throw IOException("bad frame length $len")
        val payload = ByteArray(len)
        inp.readFully(payload)
        return Frame(type, payload)
    }

    data class Frame(val type: Int, val payload: ByteArray)

    fun localIps(): List<String> {
        val result = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.startsWith("wlan") || nif.name.startsWith("ap")) {
                            result.add(0, ip)
                        } else {
                            result.add(ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.distinct()
    }

    fun formatBytes(v: Long): String = when {
        v >= 1024L * 1024 * 1024 -> "%.2f GB".format(v / 1024.0 / 1024 / 1024)
        v >= 1024L * 1024 -> "%.1f MB".format(v / 1024.0 / 1024)
        v >= 1024 -> "%.0f KB".format(v / 1024.0)
        else -> "$v B"
    }

    fun formatSpeed(bps: Long): String = when {
        bps >= 1024L * 1024 * 1024 -> "%.2f GB/s".format(bps / 1024.0 / 1024 / 1024)
        bps >= 1024L * 1024 -> "%.1f MB/s".format(bps / 1024.0 / 1024)
        bps >= 1024 -> "%.0f KB/s".format(bps / 1024.0)
        else -> "$bps B/s"
    }
}

/**
 * Pairing payload encoded into the QR code. The token is the shared secret
 * required by every TCP stream of the session.
 */
data class PairInfo(
    val ips: List<String>,
    val port: Int,
    val token: String,
    val name: String,
) {
    fun encode(): String = JSONObject().apply {
        put("v", Proto.VERSION)
        put("ips", JSONArray(ips))
        put("port", port)
        put("token", token)
        put("name", name)
    }.toString()

    companion object {
        fun decode(raw: String): PairInfo? = try {
            val text = raw.trim().removePrefix("LANFLASH:")
            val o = JSONObject(text)
            if (o.optInt("v", 0) != Proto.VERSION) null
            else {
                val arr = o.getJSONArray("ips")
                val ips = (0 until arr.length()).map { arr.getString(it) }
                val token = o.getString("token")
                if (ips.isEmpty() || token.isBlank()) null
                else PairInfo(ips, o.getInt("port"), token, o.optString("name", "对方设备"))
            }
        } catch (_: Exception) {
            null
        }
    }
}
