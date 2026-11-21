package ua.obdgps.nav

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle

/**
 * Explicit user request: maintain the saved Google route, but turn off all
 * active OBD/GNSS/network monitoring once no app screen is visible.
 * The route is NOT cleared; visible screens reconnect monitoring on resume.
 */
class NavigationApplication:Application(),Application.ActivityLifecycleCallbacks {
    private var visibleScreens=0

    override fun onCreate() {
        super.onCreate()
        if(BuildConfig.FYT7870_HEAD_UNIT) {
            val previous=Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread,error ->
                // Keep the original Android crash/reporting semantics.
                // Only save details if the user explicitly armed one test.
                FytCrashDiagnostics.recordCrash(this,thread,error)
                if(previous!=null)previous.uncaughtException(thread,error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity:Activity,state:Bundle?){}
    override fun onActivityStarted(activity:Activity){ visibleScreens++ }
    override fun onActivityResumed(activity:Activity){}
    override fun onActivityPaused(activity:Activity){}
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle){}
    override fun onActivityDestroyed(activity:Activity){}

    override fun onActivityStopped(activity:Activity) {
        visibleScreens=(visibleScreens-1).coerceAtLeast(0)
        if(visibleScreens==0 && !activity.isChangingConfigurations) {
            // stopService, not ACTION EXIT via startForegroundService:
            // this cannot accidentally start a stopped service in background.
            stopService(Intent(this,NavService::class.java))
        }
    }
}
