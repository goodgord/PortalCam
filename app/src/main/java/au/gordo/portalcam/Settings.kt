package au.gordo.portalcam

import android.content.Context
import android.content.SharedPreferences

/** The user's choices. Stored in SharedPreferences so they survive a restart of the service or the Portal. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("portalcam", Context.MODE_PRIVATE)

    var cameraId: String
        get() = prefs.getString("cameraId", "0") ?: "0"
        set(v) = prefs.edit().putString("cameraId", v).apply()

    /** Target output width. The camera's nearest 16:9 size at or below this is used. */
    var width: Int
        get() = prefs.getInt("width", 1280)
        set(v) = prefs.edit().putInt("width", v).apply()

    var height: Int
        get() = prefs.getInt("height", 720)
        set(v) = prefs.edit().putInt("height", v).apply()

    /** JPEG quality, 1 to 100. */
    var quality: Int
        get() = prefs.getInt("quality", 75)
        set(v) = prefs.edit().putInt("quality", v).apply()

    /** Extra rotation in degrees (0, 90, 180, 270), applied on top of the automatic sensor correction. -1 means "not yet chosen". */
    var rotation: Int
        get() = prefs.getInt("rotation", -1)
        set(v) = prefs.edit().putInt("rotation", v).apply()

    var mirror: Boolean
        get() = prefs.getBoolean("mirror", false)
        set(v) = prefs.edit().putBoolean("mirror", v).apply()

    /** Whether the microphone is captured and served at /audio.wav. */
    var audio: Boolean
        get() = prefs.getBoolean("audio", true)
        set(v) = prefs.edit().putBoolean("audio", v).apply()

    var port: Int
        get() = prefs.getInt("port", 8080)
        set(v) = prefs.edit().putInt("port", v).apply()

    /** Upper bound on frames per second sent to clients. 0 means every frame the camera produces. */
    var maxFps: Int
        get() = prefs.getInt("maxFps", 30)
        set(v) = prefs.edit().putInt("maxFps", v).apply()
}
