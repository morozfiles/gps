package ua.obdgps.nav

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold startup test for Android 13 AOSP, with GPS/Bluetooth permissions
 * deliberately absent. A head unit must display its dashboard anyway.
 */
@RunWith(AndroidJUnit4::class)
class FytAndroid13ColdStartTest {
    private fun buttons(v:View):List<Button> {
        val found=ArrayList<Button>()
        if(v is Button)found.add(v)
        if(v is ViewGroup){
            for(i in 0 until v.childCount)found.addAll(buttons(v.getChildAt(i)))
        }
        return found
    }
    @Test fun coldLaunchWithoutSensorsPlayServicesBluetoothOrMaps(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertEquals("ua.obdgps.nav.fyt7870",context.packageName)
        val manifest=context.packageManager.getActivityInfo(
            ComponentName(context,FytHeadUnitActivity::class.java),0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            manifest.screenOrientation)
        val prefs=context.getSharedPreferences("fyt_unit",0)
        prefs.edit().putBoolean("monitor_enabled",false)
            .putBoolean("auto_monitor",false).commit()
        FytRouteStore.clear(context)
        ActivityScenario.launch(FytHeadUnitActivity::class.java).use{scenario->
            scenario.onActivity{activity->
                assertFalse("FYT app should not request sensor permissions on cold launch",
                    activity.isFinishing)
                val content=activity.findViewById<View>(android.R.id.content)
                val buttons=buttons(content)
                assertTrue(buttons.any{it.text.contains("ВЕКТОРНАЯ КАРТА")})
                assertTrue(buttons.any{it.text.contains("VGATE")})
                assertTrue(buttons.any{it.text.startsWith("ГИРОСКОП")})
                assertTrue(buttons.any{it.text.startsWith("АКСЕЛЕРОМЕТР")})
                assertFalse(buttons.any{it.text.startsWith("Компас")})
                val preview=activity.javaClass.getDeclaredField("preview").apply{
                    isAccessible=true
                }.get(activity) as FytRoutePreviewView
                assertTrue("Route preview should be laid out",preview.width>0)
                assertTrue("Route preview should be visible",preview.height>0)
                val gps=activity.javaClass.getDeclaredField("gpsText").apply{
                    isAccessible=true
                }.get(activity) as TextView
                assertTrue(gps.text.toString().contains("GM906BT"))
                assertTrue(gps.text.toString().contains("Встроенный"))
            }
        }
    }

    @Test fun dashboardAndRoutePreviewSurviveRecreation(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val start=RoutingEngine.Point(50.4501,30.5234)
        val end=RoutingEngine.Point(50.465,30.543)
        FytRouteStore.saveRoute(context,RoutingEngine.Route(
            listOf(start,RoutingEngine.Point(50.457,30.531),end),
            emptyList(),4.7,800,end),active=true)
        val scenario=ActivityScenario.launch(FytHeadUnitActivity::class.java)
        try {
            scenario.recreate()
            scenario.onActivity {activity->
                val preview=activity.javaClass.getDeclaredField("preview").apply{
                    isAccessible=true
                }.get(activity) as FytRoutePreviewView
                assertTrue(preview.isAttachedToWindow)
                assertEquals(end,FytRouteStore.load(activity)?.destination)
                assertEquals(true,FytRouteStore.load(activity)?.active)
            }
        }finally{scenario.close();FytRouteStore.clear(context)}
    }
}
