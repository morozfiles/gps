package ua.obdgps.nav

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FytHeadUnitInstrumentedTest {
    private fun buttons(v:View):List<Button>{
        val out=ArrayList<Button>()
        if(v is Button)out.add(v)
        if(v is ViewGroup)for(i in 0 until v.childCount)
            out.addAll(buttons(v.getChildAt(i)))
        return out
    }

    @Test fun landscapeFirstScreenHasOnlyTwoMotionSensorsAndTwoGnssSources(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        FytRouteStore.clear(context)
        ActivityScenario.launch(FytHeadUnitActivity::class.java).use{scenario->
            scenario.onActivity{activity->
                val info=activity.packageManager.getActivityInfo(
                    ComponentName(activity,FytHeadUnitActivity::class.java),0)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    info.screenOrientation)
                val all=buttons(activity.findViewById(android.R.id.content))
                val gyro=all.firstOrNull {it.text.startsWith("ГИРОСКОП")}
                val accel=all.firstOrNull {it.text.startsWith("АКСЕЛЕРОМЕТР")}
                assertNotNull("Gyroscope calibration button missing",gyro)
                assertNotNull("Accelerometer calibration button missing",accel)
                assertSame("Sensors must share a horizontal row",gyro!!.parent,accel!!.parent)
                val row=gyro.parent as LinearLayout
                assertEquals(LinearLayout.HORIZONTAL,row.orientation)
                assertFalse("Head unit must not show compass",
                    all.any{it.text.startsWith("Компас")})
                val gps=activity.javaClass.getDeclaredField("gpsText").apply{
                    isAccessible=true
                }.get(activity) as android.widget.TextView
                assertTrue(gps.text.toString().contains("GM906BT"))
                assertTrue(gps.text.toString().contains("Встроенный GNSS"))
                assertTrue(all.any{it.text.startsWith("ВЕКТОРНАЯ")})
                assertTrue(all.any{it.text.contains("ТЕСТ GPS / DR")})
                assertTrue(all.any{it.text.contains("VGATE")})
            }
        }
    }

    @Test fun routeAndGuidanceAreStoredForHeadunitWakeWithoutInternet(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        FytRouteStore.clear(context)
        val destination=RoutingEngine.Point(50.4562,30.5452)
        val route=RoutingEngine.Route(
            listOf(RoutingEngine.Point(50.4501,30.5234),
                RoutingEngine.Point(50.4521,30.5321),destination),
            listOf(RoutingEngine.Turn(1,"Направо","Направо",2)),
            3.2,650,destination)
        FytRouteStore.saveRoute(context,route)
        FytRouteStore.setActive(context,true)
        val path=File(context.filesDir,"fyt-route.json.gz")
        assertTrue("Saved route file missing",path.isFile && path.length()>50)
        val restored=FytRouteStore.load(context)
        assertNotNull(restored)
        assertTrue("Active guidance not persisted",restored!!.active)
        assertEquals(3,restored.route!!.points.size)
        assertEquals("Направо",restored.route!!.maneuvers[0].instruction)
        assertEquals(destination,restored.destination)
        FytRouteStore.clear(context)
        assertNull(FytRouteStore.load(context))
    }
}
