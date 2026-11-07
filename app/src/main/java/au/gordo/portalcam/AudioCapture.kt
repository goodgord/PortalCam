package au.gordo.portalcam

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Reads the Portal's microphone as 16-bit mono PCM and hands each 20 ms chunk to every subscriber.
 * A subscriber that falls behind by more than a second loses its oldest chunks, so latency cannot build up.
 */
object AudioCapture {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val BITS = 16
    private const val TAG = "PortalCam.Audio"
    private const val CHUNK_SAMPLES = SAMPLE_RATE / 50  // 20 ms
    private const val QUEUE_CHUNKS = 50                 // 1 second

    private val subscribers = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile var state: String = "off"
    /** Peak level of the last chunk, 0 to 1, so the screen can show the mic is alive. */
    @Volatile var level: Float = 0f

    val listeners: Int get() = subscribers.size

    fun subscribe(): LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue<ByteArray>(QUEUE_CHUNKS).also { subscribers += it }
    fun unsubscribe(q: LinkedBlockingQueue<ByteArray>) { subscribers -= q }

    @Synchronized fun start() {
        if (running) return
        running = true
        worker = thread(name = "audio-capture", isDaemon = true) { loop() }
    }

    @Synchronized fun stop() {
        running = false
        worker?.join(1000); worker = null
        state = "off"; level = 0f
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, CHUNK_SAMPLES * 2 * 4)
        // VOICE_COMMUNICATION turns on the device's echo cancelling and noise suppression. Fall back to the plain mic.
        val record = listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
            try {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            } catch (e: Exception) { Log.w(TAG, "source $src: $e"); null }
        }
        if (record == null) { state = "mic unavailable"; running = false; return }
        try {
            record.startRecording()
            state = "on"
            Log.i(TAG, "Recording ${SAMPLE_RATE} Hz mono")
            while (running) {
                val chunk = ByteArray(CHUNK_SAMPLES * 2)
                var got = 0
                while (got < chunk.size && running) {
                    val n = record.read(chunk, got, chunk.size - got)
                    if (n < 0) { Log.w(TAG, "read returned $n"); running = false; break }
                    got += n
                }
                if (!running) break
                var peak = 0
                var i = 0
                while (i < chunk.size) {
                    val s = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                    val a = if (s < 0) -s else s
                    if (a > peak) peak = a
                    i += 2
                }
                // Decaying peak so the on-screen bar stays readable between words.
                level = maxOf(peak / 32768f, level * 0.85f)
                for (q in subscribers) {
                    if (!q.offer(chunk)) { q.poll(); q.offer(chunk) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture: $e"); state = "mic error: ${e.message}"
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            if (state == "on") state = "off"
        }
    }

    /** A WAV header whose data length is "unknown", which ffmpeg and OBS accept for a live stream. */
    fun wavHeader(): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        val blockAlign = CHANNELS * BITS / 8
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(-1); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(CHANNELS.toShort())
        b.putInt(SAMPLE_RATE); b.putInt(byteRate); b.putShort(blockAlign.toShort()); b.putShort(BITS.toShort())
        b.put("data".toByteArray()); b.putInt(-1)
        return b.array()
    }
}
