package ua.obdgps.nav

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * FYT Android 13 can send ACCON/ACCOFF broadcasts from vendor MCU services.
 * Best effort only: Android may forbid starting a foreground service when
 * the app is in the background. The visible dashboard handles recovery too.
 */
class FytBootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?){
        when(intent?.action){
            "com.fyt.boot.ACCOFF" -> {
                State.gpsWakeStatus="ACC выключен: маршрут сохранён"
                return
            }
            "com.fyt.boot.ACCON" ->{
                State.gpsWakeStatus="ACC включён: восстановление соединений"
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED ->{
                State.gpsWakeStatus="Android запущен: жду готовности системы"
            }
            else ->return
        }
        val prefs=context.getSharedPreferences("fyt_unit",Context.MODE_PRIVATE)
        if(!prefs.getBoolean("monitor_enabled",false))return
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
            PackageManager.PERMISSION_GRANTED)return
        try{
            context.startForegroundService(Intent(context,NavService::class.java)
                .setAction(NavService.MONITOR))
        }catch(e:Exception){
            Log.w("FytBootReceiver",
                "Vendor/background start blocked, retry after screen resume",e)
        }
    }
}
