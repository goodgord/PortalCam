package au.gordo.portalcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the stream when the Portal boots, so nobody has to touch the Portal before a call. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings(context).startOnBoot) return
        context.startForegroundService(Intent(context, CamService::class.java).setAction(CamService.ACTION_START))
    }
}
