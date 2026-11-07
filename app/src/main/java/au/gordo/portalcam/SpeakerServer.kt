package au.gordo.portalcam

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Plays audio the PC sends, so the Portal is the call's speaker.
 *
 * Protocol on TCP [port]: the client sends one ASCII header line, "PCM <sampleRate> <channels> 16\n", then raw
 * little-endian 16-bit PCM until it disconnects. One client at a time; a new client replaces the old one.
 *
 * Playback uses the voice-communication usage with the audio manager in communication mode, so the Portal's
 * echo canceller treats this as the far end and removes it from the microphone stream.
 */
class SpeakerServer(private val context: Context, private val port: Int) {
    private val tag = "PortalCam.Speaker"
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var current: Socket? = null

    companion object {
        @Volatile var state: String = "idle"
        @Volatile var level: Float = 0f
    }

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        running = true
        thread(name = "speaker-accept", isDaemon = true) {
            while (running) {
                val client = try { ss.accept() } catch (e: IOException) { if (running) Log.w(tag, "accept: $e"); break }
                current?.let { try { it.close() } catch (_: IOException) {} }
                current = client
                thread(name = "speaker-play", isDaemon = true) { play(client) }
            }
        }
        Log.i(tag, "Speaker listening on port $port")
    }

    fun stop() {
        running = false
        try { current?.close() } catch (_: IOException) {}
        try { server?.close() } catch (_: IOException) {}
        server = null
        state = "idle"
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 64) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun play(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var track: AudioTrack? = null
        try {
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val header = readLine(input).split(" ")
            if (header.size < 4 || header[0] != "PCM") { Log.w(tag, "bad header: $header"); return }
            val rate = header[1].toInt()
            val channels = header[2].toInt()
            val bits = header[3].toInt()
            if (bits != 16 || channels !in 1..2 || rate !in 8000..96000) { Log.w(tag, "unsupported: $header"); return }

            val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val frameBytes = channels * 2
            // About 60 ms of buffer: low enough delay for conversation, enough to ride over Wi-Fi jitter.
            val bufBytes = maxOf(minBuf, rate * frameBytes * 60 / 1000)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(channelMask).build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = true
            track.play()
            state = "playing $rate Hz ${channels}ch from ${socket.inetAddress.hostAddress}"
            Log.i(tag, state)

            val chunk = ByteArray(rate * frameBytes / 50)  // 20 ms
            while (running && !socket.isClosed) {
                val n = input.read(chunk)
                if (n < 0) break
                var off = 0
                while (off < n) {
                    val w = track.write(chunk, off, n - off)
                    if (w < 0) throw IOException("AudioTrack write $w")
                    off += w
                }
                var peak = 0
                var i = 0
                while (i + 1 < n) {
                    val s = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                    val a = if (s < 0) -s else s
                    if (a > peak) peak = a
                    i += 2 * 8  // sample every 8th frame; a level meter does not need more
                }
                level = maxOf(peak / 32768f, level * 0.85f)
            }
        } catch (e: IOException) {
            Log.d(tag, "speaker client: $e")
        } catch (e: Exception) {
            Log.w(tag, "speaker: $e")
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            try { socket.close() } catch (_: IOException) {}
            if (current === socket) {
                current = null
                audio.mode = AudioManager.MODE_NORMAL
                state = "idle"; level = 0f
            }
        }
    }
}
