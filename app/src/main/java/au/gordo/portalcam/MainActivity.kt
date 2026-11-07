package au.gordo.portalcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnMirror: Button
    private lateinit var btnCamera: Button
    private lateinit var btnSize: Button
    private lateinit var btnQuality: Button
    private lateinit var btnMic: Button

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var previewing = false

    private val sizes = listOf(640 to 360, 1280 to 720, 1920 to 1080)
    private val qualities = listOf(50, 65, 75, 85, 95)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        btnToggle = findViewById(R.id.btnToggle)
        btnMirror = findViewById(R.id.btnMirror)
        btnCamera = findViewById(R.id.btnCamera)
        btnSize = findViewById(R.id.btnSize)
        btnQuality = findViewById(R.id.btnQuality)
        btnMic = findViewById(R.id.btnMic)
        btnMic.setOnClickListener {
            settings.audio = !settings.audio
            send(CamService.ACTION_RESTART)
        }

        btnToggle.setOnClickListener {
            if (CamService.running) send(CamService.ACTION_STOP) else send(CamService.ACTION_START)
        }
        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            settings.rotation = (CamService.effectiveRotation + 90) % 360
            send(CamService.ACTION_RESTART)
        }
        btnMirror.setOnClickListener {
            settings.mirror = !settings.mirror
            send(CamService.ACTION_RESTART)
        }
        btnCamera.setOnClickListener {
            settings.cameraId = if (settings.cameraId == "0") "1" else "0"
            settings.rotation = -1
            send(CamService.ACTION_RESTART)
        }
        btnSize.setOnClickListener {
            val i = sizes.indexOfFirst { it.first == settings.width }
            val next = sizes[(i + 1) % sizes.size]
            settings.width = next.first; settings.height = next.second
            send(CamService.ACTION_RESTART)
        }
        btnQuality.setOnClickListener {
            val i = qualities.indexOf(settings.quality)
            settings.quality = qualities[(i + 1) % qualities.size]
            send(CamService.ACTION_RESTART)
        }

        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else send(CamService.ACTION_START)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != 1) return
        val cam = permissions.indexOf(Manifest.permission.CAMERA)
        if (cam >= 0 && grantResults.getOrNull(cam) != PackageManager.PERMISSION_GRANTED) { status.text = "Camera permission is required."; return }
        // Audio may be refused; the camera still works without it.
        send(CamService.ACTION_START)
    }

    private fun send(action: String) {
        startForegroundService(Intent(this, CamService::class.java).setAction(action))
    }

    override fun onResume() {
        super.onResume()
        previewing = true
        thread(name = "preview", isDaemon = true) { previewLoop() }
        ui.post(statusTick)
    }

    override fun onPause() {
        super.onPause()
        previewing = false
        ui.removeCallbacks(statusTick)
    }

    /** Shows the same JPEGs the PC receives, so what is on the Portal screen is what the interviewer sees. */
    private fun previewLoop() {
        var seq = 0L
        val opts = BitmapFactory.Options()
        while (previewing) {
            val next = FrameStore.awaitNewer(seq, 500) ?: continue
            seq = next.second
            val jpeg = next.first
            opts.inSampleSize = if (FrameStore.width > 1300) 2 else 1
            val bmp = try { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) } catch (e: Exception) { null } ?: continue
            ui.post { if (previewing) preview.setImageBitmap(bmp) }
            // The ImageView cannot show more than the display refresh; 20 fps on screen is plenty for framing.
            Thread.sleep(50)
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            val urls = ipv4Addresses().joinToString("\n") { "http://$it:${settings.port}/stream" }
            val lines = buildString {
                append(CamService.state).append('\n')
                if (urls.isEmpty()) append("No network. Connect the Portal to Wi-Fi.\n") else append(urls).append('\n')
                append("USB: adb forward tcp:${settings.port} tcp:${settings.port}  then  http://127.0.0.1:${settings.port}/stream\n")
                append("${FrameStore.width}x${FrameStore.height}  ${"%.0f".format(FrameStore.fps)} fps  ${FrameStore.clients.get()} viewer(s)  rot ${CamService.effectiveRotation}°\n")
                val bars = (AudioCapture.level * 20).toInt().coerceIn(0, 20)
                append("Mic ${AudioCapture.state}  [" + "#".repeat(bars) + " ".repeat(20 - bars) + "]  ${AudioCapture.listeners} listener(s)  /audio.wav")
            }
            status.text = lines
            btnToggle.text = if (CamService.running) "Stop" else "Start"
            btnMirror.text = if (settings.mirror) "Mirror: on" else "Mirror: off"
            btnCamera.text = "Camera ${settings.cameraId}"
            btnSize.text = "${settings.height}p"
            btnQuality.text = "Q ${settings.quality}"
            btnMic.text = if (settings.audio) "Mic: on" else "Mic: off"
            ui.postDelayed(this, 500)
        }
    }

    private fun ipv4Addresses(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address) out += addr.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
