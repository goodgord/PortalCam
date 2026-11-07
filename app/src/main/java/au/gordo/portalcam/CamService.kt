package au.gordo.portalcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Foreground service that keeps the camera open, turns each frame into a JPEG and hands it to [FrameStore],
 * and runs the [MjpegServer] that clients read from.
 *
 * Start:   adb shell am start-foreground-service -n au.gordo.portalcam/.CamService -a au.gordo.portalcam.START
 * Stop:    adb shell am start-foreground-service -n au.gordo.portalcam/.CamService -a au.gordo.portalcam.STOP
 */
class CamService : Service() {

    companion object {
        const val ACTION_START = "au.gordo.portalcam.START"
        const val ACTION_STOP = "au.gordo.portalcam.STOP"
        const val ACTION_RESTART = "au.gordo.portalcam.RESTART"
        private const val TAG = "PortalCam"
        private const val CHANNEL = "portalcam"
        private const val NOTIFICATION_ID = 1

        /** One line describing what the service is doing, for the activity to show. */
        @Volatile var state: String = "Stopped"
        @Volatile var running: Boolean = false
        /** The rotation in effect, so the Rotate button knows where to go next. */
        @Volatile var effectiveRotation: Int = 0
        @Volatile var cameraId: String = "0"
    }

    private lateinit var settings: Settings
    private lateinit var cameraManager: CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var server: MjpegServer? = null
    private var speaker: SpeakerServer? = null
    private var beacon: Beacon? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Reusable buffers for the frame pipeline.
    private var nv21 = ByteArray(0)
    private var transformed = ByteArray(0)
    private val jpegOut = ByteArrayOutputStream(512 * 1024)

    // Frame-rate measurement.
    private var fpsWindowStart = 0L
    private var fpsFrames = 0

    private var rotation = 0
    private var mirror = false
    private var quality = 75
    private var retryCount = 0
    private var lastBringToFront = 0L
    /** Bumped on every open attempt. Callbacks from an older attempt are ignored, so two opens never overlap. */
    private var openGeneration = 0
    private val retryRunnable = Runnable { openCamera() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "PortalCam", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            ACTION_RESTART -> { stopEverything(); startEverything() }
            else -> if (!running) startEverything()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("PortalCam")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun setState(s: String) {
        state = s
        Log.i(TAG, s)
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s)) } catch (_: Exception) {}
    }

    private fun startEverything() {
        startForeground(NOTIFICATION_ID, notification("Starting"))
        running = true
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setState("Camera permission not granted. Open the app, or run: adb shell pm grant au.gordo.portalcam android.permission.CAMERA")
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PortalCam:stream").also { it.acquire() }

        quality = settings.quality.coerceIn(10, 100)
        mirror = settings.mirror
        cameraId = settings.cameraId
        rotation = settings.rotation
        if (rotation < 0) rotation = defaultRotation(cameraId)
        effectiveRotation = rotation

        val thread = HandlerThread("camera").also { it.start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)

        try {
            server = MjpegServer(settings.port, settings.maxFps, this).also { it.start() }
        } catch (e: IOException) {
            setState("Cannot listen on port ${settings.port}: ${e.message}")
            return
        }
        if (settings.audio) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) AudioCapture.start()
            else AudioCapture.state = "no permission"
        }
        try { speaker = SpeakerServer(this, settings.speakerPort).also { it.start() } }
        catch (e: IOException) { Log.w(TAG, "speaker port ${settings.speakerPort}: $e"); SpeakerServer.state = "port busy" }
        try { beacon = Beacon(settings.beaconPort, settings.port, settings.speakerPort, android.os.Build.MODEL).also { it.start() } }
        catch (e: Exception) { Log.w(TAG, "beacon: $e") }
        openCamera()
    }

    private fun stopEverything() {
        running = false
        cameraHandler?.removeCallbacks(retryRunnable)
        openGeneration++
        AudioCapture.stop()
        closeCamera()
        server?.stop(); server = null
        speaker?.stop(); speaker = null
        beacon?.stop(); beacon = null
        cameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        FrameStore.reset()
        state = "Stopped"
    }

    /**
     * Rotation that makes the picture upright for how the Portal is standing right now, from the sensor's
     * mounting and the display rotation. Camera2's documented formula for a front camera.
     */
    private fun defaultRotation(id: String): Int {
        return try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val deviceDeg = when (display) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val r = if (front) (sensor + deviceDeg + 360) % 360 else (sensor - deviceDeg + 360) % 360
            Log.i(TAG, "sensor=$sensor display=$deviceDeg front=$front -> rotation $r")
            r
        } catch (e: Exception) { 0 }
    }

    private fun chooseSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        val wantW = settings.width
        val wantH = settings.height
        sizes.firstOrNull { it.width == wantW && it.height == wantH }?.let { return it }
        // Nearest 16:9 at or below the wanted width, else the largest at or below, else the smallest available.
        val wide = sizes.filter { it.width <= wantW && kotlin.math.abs(it.width * 9 - it.height * 16) <= it.width / 10 }
        wide.maxByOrNull { it.width }?.let { return it }
        sizes.filter { it.width <= wantW }.maxByOrNull { it.width * it.height }?.let { return it }
        return sizes.minByOrNull { it.width * it.height } ?: Size(1280, 720)
    }

    private fun openCamera() {
        if (!running) return
        val handler = cameraHandler ?: return
        if (camera != null) return
        val gen = ++openGeneration
        setState("Opening camera $cameraId")
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val size = chooseSize(chars)
            Log.i(TAG, "Using camera $cameraId at ${size.width}x${size.height}, rotation $rotation, mirror $mirror, quality $quality")
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ onFrame(it) }, handler)
            reader = r
            val fpsRange = chooseFpsRange(chars)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (gen != openGeneration || !running) { device.close(); return }
                    camera = device
                    retryCount = 0
                    createSession(device, r, fpsRange)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (gen != openGeneration) return
                    camera = null
                    scheduleRetry("Camera taken by another app, retrying")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (gen != openGeneration) return
                    camera = null
                    scheduleRetry("Camera error $error, retrying")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            if (e.message?.contains("from background") == true) {
                // Android 10 opens the camera only for the app on screen. Bring our screen back, if allowed.
                val now = System.currentTimeMillis()
                if (android.provider.Settings.canDrawOverlays(this) && now - lastBringToFront > 20_000) {
                    lastBringToFront = now
                    Log.i(TAG, "Camera blocked in background; bringing PortalCam to the front")
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    scheduleRetry("Bringing PortalCam to the front for the camera")
                } else {
                    scheduleRetry("Camera needs PortalCam on the Portal's screen. Tap PortalCam on the Portal.")
                }
            } else scheduleRetry("Camera access failed: ${e.message}")
        } catch (e: SecurityException) {
            setState("Camera permission missing")
        } catch (e: Exception) {
            scheduleRetry("Camera open failed: $e")
        }
    }

    private fun chooseFpsRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        // Prefer a fixed 30, then the range with the highest floor whose ceiling is 30, then the widest.
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun createSession(device: CameraDevice, r: ImageReader, fpsRange: Range<Int>?) {
        val handler = cameraHandler ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(request.build(), null, handler)
                        setState("Streaming")
                    } catch (e: Exception) { scheduleRetry("Capture failed: $e") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { scheduleRetry("Camera session failed") }
            }, handler)
        } catch (e: Exception) {
            scheduleRetry("Camera session error: $e")
        }
    }

    private fun scheduleRetry(reason: String) {
        if (!running) return
        setState(reason)
        closeCamera(keepReader = false)
        retryCount++
        val delay = (1000L * minOf(retryCount, 5))
        // One pending retry at a time.
        cameraHandler?.removeCallbacks(retryRunnable)
        cameraHandler?.postDelayed(retryRunnable, delay)
    }

    private fun closeCamera(keepReader: Boolean = false) {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        if (!keepReader) { try { reader?.close() } catch (_: Exception) {}; reader = null }
    }

    private fun onFrame(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val w = image.width
            val h = image.height
            val need = w * h * 3 / 2
            if (nv21.size != need) { nv21 = ByteArray(need); transformed = ByteArray(need) }
            Yuv.toNv21(image, nv21)
            image.close()

            var outW = w
            var outH = h
            var data = nv21
            if (rotation != 0 || mirror) {
                val (tw, th) = Yuv.transform(nv21, w, h, rotation, mirror, transformed)
                outW = tw; outH = th; data = transformed
            }
            jpegOut.reset()
            YuvImage(data, ImageFormat.NV21, outW, outH, null).compressToJpeg(Rect(0, 0, outW, outH), quality, jpegOut)
            FrameStore.width = outW
            FrameStore.height = outH
            FrameStore.publish(jpegOut.toByteArray())

            val now = System.nanoTime()
            fpsFrames++
            if (fpsWindowStart == 0L) fpsWindowStart = now
            else if (now - fpsWindowStart >= 1_000_000_000L) {
                FrameStore.fps = fpsFrames * 1e9 / (now - fpsWindowStart)
                fpsFrames = 0
                fpsWindowStart = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame: $e")
            try { image.close() } catch (_: Exception) {}
        }
    }
}
