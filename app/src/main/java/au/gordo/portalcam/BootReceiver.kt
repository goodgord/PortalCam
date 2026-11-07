package au.gordo.portalcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the stream when the Portal boots, so nobody has to touch the Portal before a call.
 *
 * It opens the app's screen rather than the service alone: Android 10 refuses to open the camera for an app that
 * has not been on screen since boot ("cannot open camera from background"). Opening an activity from a boot
 * broadcast needs the SYSTEM_ALERT_WINDOW permission, granted once over adb. If that permission is missing the
 * service is still started, which gives the microphone and speaker, and the camera follows once someone opens
 * the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings(context).startOnBoot) return
        context.startForegroundService(Intent(context, CamService::class.java).setAction(CamService.ACTION_START))
        if (android.provider.Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
