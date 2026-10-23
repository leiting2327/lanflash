package com.lanflash.app.net

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

data class SendProgress(
    val sentBytes: Long = 0,
    val totalBytes: Long = 0,
    val speed: Long = 0,
    val fileCount: Int = 0,
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * Sends a batch of files over N parallel TCP connections. Chunk k is assigned
 * to connection k % N, so a single large file still saturates the link.
 */
class Sender(private val ctx: Context) {

    private data class FileMeta(
        val id: Int,
        val name: String,
        val size: Long,
        val pfd: ParcelFileDescriptor,
        val channel: FileChannel,
    )

    private data class Chunk(val fileId: Int, val offset: Long, val length: Int)

    private class Stream(val socket: Socket, val inp: DataInputStream, val out: BufferedOutputStream)

    suspend fun send(
        pair: PairInfo,
        uris: List<Uri>,
        onProgress: (SendProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sendInternal(pair, uris, onProgress)
            Result.success(Unit)
        } catch (t: Throwable) {
            onProgress(SendProgress(done = true, error = t.message ?: t.javaClass.simpleName))
            Result.failure(t)
        }
    }

    private suspend fun sendInternal(pair: PairInfo, uris: List<Uri>, onProgress: (SendProgress) -> Unit) {
        if (uris.isEmpty()) throw IllegalArgumentException("请选择要发送的文件")
        if (uris.size > 200) throw IllegalArgumentException("单次最多 200 个文件")

        val resolver = ctx.applicationContext.contentResolver
        val metas = ArrayList<FileMeta>(uris.size)
        val sockets = ArrayList<Socket>()
        var totalBytes = 0L

        try {
            for ((i, uri) in uris.withIndex()) {
                var name = "file_$i"
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (ni >= 0) name = c.getString(ni) ?: name
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                val pfd = resolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("无法读取文件：$name")
                if (size < 0) size = pfd.statSize
                if (size < 0) size = 0
                val channel = FileInputStream(pfd.fileDescriptor).channel
                metas.add(FileMeta(i, name, size, pfd, channel))
                totalBytes += size
            }

            // Build the global chunk plan. Both ends derive identical chunk
            // indices from the manifest, which is what makes resume/retransmit
            // bookkeeping possible.
            val plan = ArrayList<Chunk>()
            for (m in metas) {
                var offset = 0L
                while (offset < m.size) {
                    val len = minOf(Proto.CHUNK_SIZE.toLong(), m.size - offset).toInt()
                    plan.add(Chunk(m.id, offset, len))
                    offset += len
                }
            }

            val streamCount = minOf(Proto.DEFAULT_STREAMS, maxOf(1, plan.size))
            val ip = connectAny(pair)
            for (i in 0 until streamCount) {
                val s = Socket()
                s.tcpNoDelay = true
                s.sendBufferSize = 4 * 1024 * 1024
                s.receiveBufferSize = 1 * 1024 * 1024
                s.soTimeout = 120_000
                s.connect(InetSocketAddress(ip, pair.port), 5000)
                sockets.add(s)
            }

            val streams = sockets.mapIndexed { i, s ->
                val out = BufferedOutputStream(s.getOutputStream(), 1 shl 20)
                Proto.writeFrame(
                    out, Proto.T_HELLO,
                    JSONObject()
                        .put("v", Proto.VERSION)
                        .put("token", pair.token)
                        .put("stream", i)
                        .put("streams", streamCount)
                        .put("name", android.os.Build.MODEL ?: "Android")
                        .toString().toByteArray(),
                )
                Stream(s, DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16)), out)
            }

            // Manifest travels on stream 0 before any payload.
            val manifest = JSONObject().put("chunkSize", Proto.CHUNK_SIZE)
            val filesJson = JSONArray()
            for (m in metas) {
                filesJson.put(JSONObject().put("id", m.id).put("name", m.name).put("size", m.size))
            }
            manifest.put("files", filesJson)
            Proto.writeFrame(streams[0].out, Proto.T_MANIFEST, manifest.toString().toByteArray())
            streams.forEach { it.out.flush() }

            val sent = AtomicLong(0)
            val progressJob = startProgressJob(totalBytes, metas.size, sent, onProgress)

            try {
                coroutineScope {
                    for (i in 0 until streamCount) {
                        launch(Dispatchers.IO) {
                            var idx = i
                            while (idx < plan.size) {
                                val chunk = plan[idx]
                                val meta = metas[chunk.fileId]
                                writeChunk(streams[i].out, meta, chunk)
                                sent.addAndGet(chunk.length.toLong())
                                idx += streamCount
                            }
                            streams[i].out.flush()
                        }
                    }
                }

                // All data is on the wire. Ask the receiver to verify.
                var missing = ArrayList<Int>()
                for (round in 0..2) {
                    Proto.writeFrame(streams[0].out, Proto.T_DONE, JSONObject().put("total", plan.size).toString().toByteArray())
                    streams[0].out.flush()
                    val reply = Proto.readFrame(streams[0].inp)
                    if (reply.type != Proto.T_COMPLETE) throw IllegalStateException("接收端未响应")
                    val obj = JSONObject(String(reply.payload, Charsets.UTF_8))
                    if (obj.optBoolean("ok", false)) {
                        missing = ArrayList()
                        break
                    }
                    val arr = obj.optJSONArray("missing") ?: JSONArray()
                    missing = (0 until arr.length()).map { arr.getInt(it) }.toMutableList() as ArrayList<Int>
                    if (missing.isEmpty()) break
                    for (ci in missing) {
                        if (ci !in plan.indices) continue
                        val chunk = plan[ci]
                        writeChunk(streams[0].out, metas[chunk.fileId], chunk)
                    }
                    streams[0].out.flush()
                }

                if (missing.isNotEmpty()) {
                    throw IllegalStateException("传输校验失败，重传后仍有 ${missing.size} 个分块缺失")
                }
            } finally {
                progressJob?.cancel()
            }

            onProgress(
                SendProgress(
                    sentBytes = totalBytes,
                    totalBytes = totalBytes,
                    speed = 0,
                    fileCount = metas.size,
                    done = true,
                )
            )
        } finally {
            for (m in metas) {
                try { m.channel.close() } catch (_: Exception) {}
                try { m.pfd.close() } catch (_: Exception) {}
            }
            for (s in sockets) {
                try { s.close() } catch (_: Exception) {}
            }
        }
    }

    private fun connectAny(pair: PairInfo): String {
        var last: Exception? = null
        for (ip in pair.ips) {
            val probe = Socket()
            try {
                probe.tcpNoDelay = true
                probe.connect(InetSocketAddress(ip, pair.port), 3000)
                return ip
            } catch (e: Exception) {
                last = e
            } finally {
                try { probe.close() } catch (_: Exception) {}
            }
        }
        throw IllegalStateException(
            "无法连接接收端（${pair.ips.joinToString()} : ${pair.port}）。请确认两台设备在同一 Wi-Fi、且接收端页面保持打开。",
            last,
        )
    }

    private fun startProgressJob(
        totalBytes: Long,
        fileCount: Int,
        sent: AtomicLong,
        onProgress: (SendProgress) -> Unit,
    ): Job? {
        val scope = CoroutineScope(Dispatchers.IO)
        var lastBytes = 0L
        var lastAt = System.currentTimeMillis()
        return scope.launch {
            while (isActive) {
                delay(300)
                val now = System.currentTimeMillis()
                val bytes = sent.get()
                val dt = (now - lastAt).coerceAtLeast(1)
                val speed = ((bytes - lastBytes) * 1000 / dt).coerceAtLeast(0)
                lastBytes = bytes
                lastAt = now
                onProgress(
                    SendProgress(
                        sentBytes = bytes,
                        totalBytes = totalBytes,
                        speed = speed,
                        fileCount = fileCount,
                    )
                )
            }
        }
    }

    private fun writeChunk(out: OutputStream, meta: FileMeta, chunk: Chunk) {
        val data = ByteArray(chunk.length)
        val bb = ByteBuffer.wrap(data)
        var pos = chunk.offset
        while (bb.hasRemaining()) {
            val n = meta.channel.read(bb, pos)
            if (n < 0) break
            pos += n
        }
        val crc = CRC32()
        crc.update(data, 0, data.size)
        val header = ByteBuffer.allocate(20)
        header.putInt(chunk.fileId)
        header.putLong(chunk.offset)
        header.putInt(crc.value.toInt())
        header.putInt(chunk.length)
        out.write(Proto.T_DATA)
        val frameLen = 20 + data.size
        out.write((frameLen ushr 24) and 0xFF)
        out.write((frameLen ushr 16) and 0xFF)
        out.write((frameLen ushr 8) and 0xFF)
        out.write(frameLen and 0xFF)
        out.write(header.array())
        out.write(data)
    }
}
