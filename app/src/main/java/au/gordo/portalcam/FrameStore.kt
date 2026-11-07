package au.gordo.portalcam

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds the most recent JPEG frame. The camera thread publishes; HTTP client threads and the preview wait for the
 * next frame. Only the latest frame is kept, so a slow client skips frames instead of lagging behind.
 */
object FrameStore {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile private var frame: ByteArray = ByteArray(0)
    @Volatile private var sequence: Long = 0

    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var fps: Double = 0.0
    val clients = AtomicInteger(0)

    fun publish(jpeg: ByteArray) {
        lock.withLock {
            frame = jpeg
            sequence++
            changed.signalAll()
        }
    }

    fun latest(): Pair<ByteArray, Long> = lock.withLock { frame to sequence }

    /** Blocks until a frame newer than [after] exists, or [timeoutMs] passes. Returns null on timeout. */
    fun awaitNewer(after: Long, timeoutMs: Long): Pair<ByteArray, Long>? {
        lock.withLock {
            var remainingNs = timeoutMs * 1_000_000
            while (sequence <= after) {
                if (remainingNs <= 0) return null
                remainingNs = changed.awaitNanos(remainingNs)
            }
            return frame to sequence
        }
    }

    fun reset() {
        lock.withLock {
            frame = ByteArray(0)
            fps = 0.0
            changed.signalAll()
        }
    }
}
