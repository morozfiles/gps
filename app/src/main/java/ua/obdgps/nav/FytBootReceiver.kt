package ua.obdgps.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Best-effort monitor recovery after an actual head-unit reboot or app update.
 * Android may block location FGS creation from BOOT_COMPLETED on some OS
 * versions; opening the dashboard retries from a visible Activity.
 */
class FytBootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?){
        if(intent?.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        if(!context.getSharedPreferences("fyt_unit",Context.MODE_PRIVATE)
                .getBoolean("auto_monitor",false))return
        try {
            context.startForegroundService(Intent(context,NavService::class.java)
                .setAction(NavService.MONITOR))
        } catch(e:Exception) {
            Log.w("FytBootReceiver",
                "Background start blocked: will retry on dashboard resume",e)
        }
    }
}
