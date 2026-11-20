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
                val gps=all.firstOrNull{it.text.startsWith("GPS ")}
                val obd=all.firstOrNull{it.text.startsWith("OBD ")}
                assertNotNull("GPS speed gauge missing",gps)
                assertNotNull("OBD speed gauge missing",obd)
                assertSame("Sensors must share a horizontal row",gyro!!.parent,accel!!.parent)
                assertSame("GPS speed must share sensor row",gyro.parent,gps!!.parent)
                assertSame("OBD speed must share sensor row",gyro.parent,obd!!.parent)
                val row=gyro.parent as LinearLayout
                assertEquals(LinearLayout.HORIZONTAL,row.orientation)
                assertEquals("Four equal horizontally placed gauge cards",4,row.childCount)
                assertTrue(all.any{it.text.startsWith("ОБЫЧНАЯ")})
                val firstNavigation=all.first{it.text.startsWith("ОБЫЧНАЯ")}
                val firstRail=(firstNavigation.parent as LinearLayout).getChildAt(0) as Button
                assertSame("Standard map is the first action",firstNavigation,firstRail)
                assertFalse("Head unit must not show compass",
                    all.any{it.text.startsWith("Компас")})
                val gpsPanel=activity.javaClass.getDeclaredField("gpsText").apply{
                    isAccessible=true
                }.get(activity) as android.widget.TextView
                assertTrue(gpsPanel.text.toString().contains("GM906BT"))
                assertTrue(gpsPanel.text.toString().contains("Встроенный GNSS"))
                assertTrue(all.any{it.text.startsWith("ВЕКТОРНАЯ")})
                assertTrue(all.any{it.text.contains("ТЕСТ GPS / DR")})
                assertTrue(all.any{it.text.contains("VGATE")})
            }
        }
    }

    @Test fun comparisonAcceptsFreshIndependentNetworkFixOneKilometreAway(){
        val clock=android.os.SystemClock.elapsedRealtime()
        val comparison=IndependentNetworkDr()
        comparison.start(50.4501,30.5234,0.0,clock)
        val correction=android.location.Location(
            android.location.LocationManager.NETWORK_PROVIDER).apply{
            latitude=50.4591 // about 1 km north
            longitude=30.5234
            accuracy=20f
            elapsedRealtimeNanos=android.os.SystemClock.elapsedRealtimeNanos()
        }
        val approved=comparison.onIndependentNetworkLocation(
            correction,"Wi-Fi/сеть",android.os.SystemClock.elapsedRealtime(),
            gyroFresh=false)
        assertTrue("Precise network correction at 1 km must be accepted",
            approved)
        assertEquals(1,comparison.correctionCount)
        val raw=comparison.trainingPose!!
        val blue=comparison.pose!!
        val rawFix=android.location.Location("training").apply{
            latitude=raw.lat;longitude=raw.lon
        }
        val blueFix=android.location.Location("blue").apply{
            latitude=blue.lat;longitude=blue.lon
        }
        assertTrue("Raw training DR must not be snapped to the network",
            rawFix.distanceTo(correction)>900f)
        assertTrue("Blue display DR should follow the accurate network fix",
            blueFix.distanceTo(correction)<200f)
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
