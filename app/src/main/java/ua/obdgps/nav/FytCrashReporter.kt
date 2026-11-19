package ua.obdgps.nav

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * FYT vendor ROMs sometimes silently close a sideloaded Activity.
 * Save a readable report synchronously so a subsequent launch can show it.
 */
class FytCrashReporter:Application(){
    override fun onCreate(){
        super.onCreate()
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler{thread,error->
            runCatching {
                record(this,thread.name,error)
            }
            if(previous!=null)previous.uncaughtException(thread,error)
            else {
                Log.e("FytFatal","Uncaught Android failure",error)
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    companion object {
        fun record(context:Context,stage:String,error:Throwable){
            val body="FYT 7870 / Android SDK ${Build.VERSION.SDK_INT}\n"+
                "ROM: ${Build.DISPLAY}\n"+
                "Этап: $stage\n"+
                Log.getStackTraceString(error).take(14000)
            context.getSharedPreferences("fyt_diagnostics",Context.MODE_PRIVATE)
                .edit().putString("last_crash",body)
                .putBoolean("unread",true).commit()
            Log.e("FytFatal",stage,error)
        }

        fun latest(context:Context)=context.getSharedPreferences(
            "fyt_diagnostics",Context.MODE_PRIVATE)
            .getString("last_crash",null)

        fun acknowledge(context:Context)=context.getSharedPreferences(
            "fyt_diagnostics",Context.MODE_PRIVATE)
            .edit().putBoolean("unread",false).apply()

        fun unread(context:Context)=context.getSharedPreferences(
            "fyt_diagnostics",Context.MODE_PRIVATE)
            .getBoolean("unread",false)
    }
}
