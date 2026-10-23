package com.lanflash.app.net

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

data class RecvFile(val name: String, val size: Long, val path: String = "")

data class RecvState(
    val active: Boolean = false,
    val waiting: Boolean = false,
    val files: List<RecvFile> = emptyList(),
    val received: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val finished: Boolean = false,
    val error: String? = null,
    val port: Int = 0,
    val ips: List<String> = emptyList(),
    val pairText: String = "",
)

/**
 * Receives a session over N parallel TCP connections. Every chunk is written
 * at its absolute offset, so the incoming streams never contend for a
 * sequential write head.
 */
class Receiver(private val ctx: Context) {

    private class Session(val dir: File) {
        val manifestReady = CompletableDeferred<Unit>()
        @Volatile var files: List<RecvFile> = emptyList()
        @Volatile var chunkSize: Int = Proto.CHUNK_SIZE
        val handles = ConcurrentHashMap<Int, RandomAccessFile>()
        @Volatile var chunkBase: IntArray = IntArray(0)
        @Volatile var bitmap: BooleanArray = BooleanArray(0)
        val receivedBytes = AtomicLong(0)
        val totalBytes = AtomicLong(0)
        @Volatile var finished = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var tickerJob: Job? = null
    private var session: Session? = null

    @Volatile private var running = false
    @Volatile private var token: String = ""

    private val _state = MutableStateFlow(RecvState())
    val state: StateFlow<RecvState> = _state.asStateFlow()

    fun start(): PairInfo {
        stop(keepState = false)

        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(0), 64)
        serverSocket = ss
        token = Proto.randomToken()
        running = true

        val dir = File(
            ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir,
            "LanFlash"
        ).apply { mkdirs() }
        session = Session(dir)

        val ips = Proto.localIps()
        val pair = PairInfo(ips, ss.localPort, token, Build.MODEL ?: "Android")
        _state.value = RecvState(
            active = true,
            waiting = true,
            port = ss.localPort,
            ips = ips,
            pairText = pair.encode(),
        )

        acceptJob = scope.launch { acceptLoop(ss) }
        startTicker()
        return pair
    }

    fun stop(keepState: Boolean = false) {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        tickerJob?.cancel()
        tickerJob = null
        session?.handles?.values?.forEach { try { it.close() } catch (_: Exception) {} }
        session?.handles?.clear()
        session = null
        if (!keepState) {
            _state.value = RecvState()
        } else {
            _state.value = _state.value.copy(active = false, waiting = false)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var lastBytes = 0L
            var lastAt = System.currentTimeMillis()
            while (running) {
                delay(300)
                val s = session ?: break
                val now = System.currentTimeMillis()
                val bytes = s.receivedBytes.get()
                val dt = (now - lastAt).coerceAtLeast(1)
                val speed = ((bytes - lastBytes) * 1000 / dt).coerceAtLeast(0)
                lastBytes = bytes
                lastAt = now
                if (!s.finished) {
                    _state.value = _state.value.copy(
                        received = bytes,
                        total = s.totalBytes.get(),
                        speed = speed,
                        waiting = s.files.isEmpty(),
                        files = s.files,
                    )
                }
            }
        }
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            scope.launch(Dispatchers.IO) { handleSocket(socket) }
        }
    }

    private suspend fun handleSocket(socket: Socket) {
        socket.use { s ->
            try {
                s.tcpNoDelay = true
                s.receiveBufferSize = 4 * 1024 * 1024
                s.soTimeout = 120_000
                val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 20))
                val out = BufferedOutputStream(s.getOutputStream(), 1 shl 16)

                val first = Proto.readFrame(inp)
                if (first.type != Proto.T_HELLO) return
                val hello = JSONObject(String(first.payload, Charsets.UTF_8))
                if (hello.optString("token") != token) {
                    Proto.writeFrame(out, Proto.T_ERROR, "bad token".toByteArray())
                    out.flush()
                    return
                }
                val streamIndex = hello.optInt("stream", 0)
                val ss = session ?: return

                if (streamIndex == 0) {
                    val mf = Proto.readFrame(inp)
                    if (mf.type != Proto.T_MANIFEST) return
                    prepareManifest(ss, JSONObject(String(mf.payload, Charsets.UTF_8)))
                    ss.manifestReady.complete(Unit)
                } else {
                    val ready = withTimeoutOrNull(30_000) { ss.manifestReady.await() }
                    if (ready == null) return
                }

                while (running && !ss.finished) {
                    val frame = Proto.readFrame(inp)
                    when (frame.type) {
                        Proto.T_DATA -> writeChunk(ss, frame.payload)
                        Proto.T_DONE -> {
                            val ok = checkComplete(ss)
                            val resp = JSONObject().put("ok", ok)
                            if (!ok) resp.put("missing", JSONArray(missingChunks(ss)))
                            Proto.writeFrame(out, Proto.T_COMPLETE, resp.toString().toByteArray())
                            out.flush()
                            if (ok) {
                                ss.finished = true
                                finish(ss)
                                return
                            }
                        }
                        Proto.T_ERROR -> return
                        else -> {}
                    }
                }
            } catch (_: Exception) {
                // A dropped stream is fine: the sender reports missing chunks
                // and retransmits them on stream 0.
            }
        }
    }

    private fun prepareManifest(ss: Session, json: JSONObject) {
        val chunkSize = json.optInt("chunkSize", Proto.CHUNK_SIZE).coerceIn(64 * 1024, 16 * 1024 * 1024)
        val arr = json.getJSONArray("files")
        val files = ArrayList<RecvFile>(arr.length())
        val base = IntArray(arr.length())
        var chunks = 0
        var total = 0L

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = uniqueName(ss.dir, sanitize(o.optString("name", "file_$i")))
            val size = o.optLong("size", 0).coerceAtLeast(0)
            val file = File(ss.dir, name)
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(size)
            ss.handles[i] = raf
            files.add(RecvFile(name, size, file.absolutePath))
            base[i] = chunks
            chunks += ((size + chunkSize - 1) / chunkSize).toInt()
            total += size
        }

        ss.chunkSize = chunkSize
        ss.files = files
        ss.chunkBase = base
        ss.bitmap = BooleanArray(chunks)
        ss.totalBytes.set(total)
        _state.value = _state.value.copy(
            waiting = false,
            files = files,
            total = total,
            received = 0,
        )
    }

    private fun writeChunk(ss: Session, payload: ByteArray) {
        if (payload.size < 20) return
        val buf = ByteBuffer.wrap(payload)
        val fileId = buf.int
        val offset = buf.long
        val crcExpected = buf.int
        val len = buf.int
        if (len < 0 || len > payload.size - 20) return
        val data = ByteArray(len)
        buf.get(data)

        val crc = CRC32()
        crc.update(data, 0, len)
        if (crc.value.toInt() != crcExpected) return

        val raf = ss.handles[fileId] ?: return
        val channel = raf.channel
        val bb = ByteBuffer.wrap(data)
        var pos = offset
        while (bb.hasRemaining()) {
            pos += channel.write(bb, pos)
        }

        val chunkIndex = ss.chunkBase[fileId] + (offset / ss.chunkSize).toInt()
        synchronized(ss.bitmap) {
            if (chunkIndex in ss.bitmap.indices && !ss.bitmap[chunkIndex]) {
                ss.bitmap[chunkIndex] = true
                ss.receivedBytes.addAndGet(len.toLong())
            }
        }
    }

    private fun checkComplete(ss: Session): Boolean = synchronized(ss.bitmap) {
        ss.bitmap.all { it }
    }

    private fun missingChunks(ss: Session): List<Int> = synchronized(ss.bitmap) {
        val out = ArrayList<Int>()
        for (i in ss.bitmap.indices) {
            if (!ss.bitmap[i]) {
                out.add(i)
                if (out.size >= 128) break
            }
        }
        out
    }

    private fun finish(ss: Session) {
        ss.handles.values.forEach { try { it.close() } catch (_: Exception) {} }
        ss.handles.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        running = false
        tickerJob?.cancel()
        tickerJob = null

        val files = ss.files
        _state.value = RecvState(
            active = false,
            finished = true,
            files = files,
            received = ss.totalBytes.get(),
            total = ss.totalBytes.get(),
        )
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[\\u0000-\\u001f]"), "_").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else cleaned
    }

    private fun uniqueName(dir: File, name: String): String {
        var candidate = name
        var i = 1
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        while (File(dir, candidate).exists()) {
            candidate = "$stem ($i)$ext"
            i++
        }
        return candidate
    }
}
