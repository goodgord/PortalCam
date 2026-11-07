package au.gordo.portalcam

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * A small HTTP server that serves the camera as Motion JPEG.
 *
 *   /            a test page with the live picture
 *   /stream      multipart/x-mixed-replace MJPEG, the stream OBS or a browser plays
 *   /snapshot.jpg the latest frame
 *   /status      a line of JSON with resolution, fps and client count
 *   /audio.wav   the microphone as a live 48 kHz 16-bit mono WAV stream
 */
class MjpegServer(private val port: Int, private val maxFps: Int) {
    private val tag = "PortalCam.Http"
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        running = true
        thread(name = "mjpeg-accept", isDaemon = true) {
            while (running) {
                val client = try { ss.accept() } catch (e: IOException) { if (running) Log.w(tag, "accept: $e"); break }
                thread(name = "mjpeg-client", isDaemon = true) { serve(client) }
            }
        }
        Log.i(tag, "Listening on port $port")
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 10_000
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            // Drain headers.
            while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break }
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val out = socket.getOutputStream()
            socket.soTimeout = 0
            when (path) {
                "/stream", "/video", "/mjpeg", "/stream.mjpg" -> stream(out)
                "/snapshot.jpg", "/snapshot", "/frame.jpg" -> snapshot(out)
                "/status", "/status.json" -> status(out)
                "/audio.wav", "/audio" -> audio(out)
                "/" , "/index.html" -> page(out)
                else -> text(out, "404 Not Found", "text/plain", "Not found\n")
            }
        } catch (e: SocketException) {
            // Client went away. Normal.
        } catch (e: IOException) {
            Log.d(tag, "client: $e")
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun header(status: String, contentType: String, length: Int?): String {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
        sb.append("Pragma: no-cache\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString()
    }

    private fun text(out: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        out.write(header(status, contentType, bytes.size).toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun snapshot(out: OutputStream) {
        var (frame, _) = FrameStore.latest()
        if (frame.isEmpty()) frame = FrameStore.awaitNewer(0, 3000)?.first ?: ByteArray(0)
        if (frame.isEmpty()) { text(out, "503 Service Unavailable", "text/plain", "No frame yet\n"); return }
        out.write(header("200 OK", "image/jpeg", frame.size).toByteArray(StandardCharsets.US_ASCII))
        out.write(frame)
        out.flush()
    }

    private fun status(out: OutputStream) {
        val json = """{"width":${FrameStore.width},"height":${FrameStore.height},"fps":${"%.1f".format(FrameStore.fps)},"clients":${FrameStore.clients.get()},"mic":"${AudioCapture.state}","micLevel":${"%.2f".format(AudioCapture.level)},"micListeners":${AudioCapture.listeners}}""" + "\n"
        text(out, "200 OK", "application/json", json)
    }

    private fun page(out: OutputStream) {
        val html = """
            <!doctype html><html><head><meta charset="utf-8"><title>PortalCam</title>
            <style>body{margin:0;background:#111;color:#ddd;font:14px sans-serif;text-align:center}
            img{max-width:100vw;max-height:90vh}p{margin:8px}</style></head>
            <body><img src="/stream" alt="PortalCam live"><p id="s">Stream: <code>/stream</code> · Snapshot: <code>/snapshot.jpg</code></p>
            <script>setInterval(async()=>{try{const r=await fetch('/status');const j=await r.json();
            document.getElementById('s').textContent=j.width+'x'+j.height+' @ '+j.fps+' fps, '+j.clients+' viewer(s)';}catch(e){}},1000)</script>
            </body></html>
        """.trimIndent()
        text(out, "200 OK", "text/html; charset=utf-8", html)
    }

    private fun audio(out: OutputStream) {
        if (AudioCapture.state != "on") { text(out, "503 Service Unavailable", "text/plain", "Microphone is ${AudioCapture.state}\n"); return }
        out.write(header("200 OK", "audio/wav", null).toByteArray(StandardCharsets.US_ASCII))
        out.write(AudioCapture.wavHeader())
        out.flush()
        val q = AudioCapture.subscribe()
        try {
            while (running && AudioCapture.state == "on") {
                val chunk = q.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                out.write(chunk)
                // Do not flush every 20 ms chunk; the socket has TCP_NODELAY, so the write goes out at once.
            }
        } finally {
            AudioCapture.unsubscribe(q)
        }
    }

    private fun stream(out: OutputStream) {
        val boundary = "portalcamframe"
        out.write(header("200 OK", "multipart/x-mixed-replace; boundary=$boundary", null).toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        FrameStore.clients.incrementAndGet()
        val minIntervalNs = if (maxFps > 0) 1_000_000_000L / maxFps else 0L
        var nextSendNs = 0L
        var seq = 0L
        try {
            while (running) {
                val next = FrameStore.awaitNewer(seq, 5000) ?: continue
                seq = next.second
                if (minIntervalNs > 0) {
                    // Pace by schedule, not by the gap since the last send, so jitter around the interval
                    // does not drop frames when the camera runs at about maxFps.
                    val now = System.nanoTime()
                    if (now < nextSendNs) continue
                    nextSendNs = if (now - nextSendNs > minIntervalNs) now + minIntervalNs else nextSendNs + minIntervalNs
                }
                val frame = next.first
                val part = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                out.write(part.toByteArray(StandardCharsets.US_ASCII))
                out.write(frame)
                out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
            }
        } finally {
            FrameStore.clients.decrementAndGet()
        }
    }
}
