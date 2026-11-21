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
