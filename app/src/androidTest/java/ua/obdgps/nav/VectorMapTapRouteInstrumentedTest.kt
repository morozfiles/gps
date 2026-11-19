package ua.obdgps.nav

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith

/**
 * End-to-end touch-dispatch test on a real Android MapView, using the
 * Mapsforge project's own tiny 0.25.0 test .map fixture.
 *
 * Routing is injected deterministically to verify five complete UI
 * destination -> route flows, independently from public server rate limits.
 * The production RoutingEngine still talks to Valhalla over the network.
 */
@RunWith(AndroidJUnit4::class)
class VectorMapTapRouteInstrumentedTest {
    private fun findMap(v:View):org.mapsforge.map.android.view.MapView? {
        if(v is org.mapsforge.map.android.view.MapView)return v
        if(v is ViewGroup){
            for(i in 0 until v.childCount)
                findMap(v.getChildAt(i))?.let{return it}
        }
        return null
    }

    private fun findButton(v:View,text:String):Button?{
        if(v is Button && v.text.toString()==text)return v
        if(v is ViewGroup){
            for(i in 0 until v.childCount)
                findButton(v.getChildAt(i),text)?.let{return it}
        }
        return null
    }

    @Test fun fiveSeparateTapsProduceFiveDistinctPinsAndFiveRoutes(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        // The fixture is only installed into this test application's data
        // directory. Tests never run on or delete user's saved maps.
        val file=OfflineRegionPacks.file(target,"kyiv","map")
        instrumentation.context.assets.open("mapsforge-test.map").use{input->
            file.outputStream().use{input.copyTo(it)}
        }
        assertTrue(OfflineUkraineAssets.validMapHeader(file))
        State.navFix=State.NavFix(
            50.4501,30.5234,0.0,5f,"Ручная",SystemClock.elapsedRealtime())
        State.manualStart=NavigatorPrefs.ManualStart(50.4501,30.5234,0.0)
        State.navigationDestination=null

        val scenario=ActivityScenario.launch(
            OfflineVectorNavigatorActivity::class.java)
        try {
            scenario.onActivity{activity->
                val routingField=activity.javaClass.getDeclaredField("routing")
                routingField.isAccessible=true
                routingField.set(activity,object:RoutingEngine(){
                    override fun request(
                        from:Point,to:Point,callback:(Result<Route>)->Unit
                    ){
                        val mid=Point((from.lat+to.lat)/2,(from.lon+to.lon)/2)
                        callback(Result.success(Route(
                            listOf(from,mid,to),
                            listOf(Turn(1,"Продолжайте движение",
                                "Продолжайте движение",0)),
                            meters(from,to)/1000.0,90,to)))
                    }
                })
            }
            instrumentation.waitForIdleSync()
            val taps=listOf(
                .18f to .23f, .32f to .60f, .57f to .35f,
                .70f to .76f, .80f to .58f)
            val visited=mutableSetOf<Pair<Double,Double>>()
            for((index,fraction) in taps.withIndex()){
                var tapX=0f
                var tapY=0f
                scenario.onActivity{activity->
                    val map=findMap(activity.window.decorView)
                        ?:error("Mapsforge MapView missing")
                    assertTrue("MapView not laid out",map.width>200 && map.height>200)
                    if(index%2==1){
                        val select=findButton(activity.window.decorView,
                            "Цель на карте")?:error("No select button")
                        select.performClick()
                    }
                    tapX=map.width*fraction.first
                    tapY=map.height*fraction.second
                    val now=SystemClock.uptimeMillis()
                    val down=MotionEvent.obtain(now,now,
                        MotionEvent.ACTION_DOWN,tapX,tapY,0)
                    val up=MotionEvent.obtain(now,now+85,
                        MotionEvent.ACTION_UP,tapX,tapY,0)
                    try {
                        map.dispatchTouchEvent(down)
                        map.dispatchTouchEvent(up)
                        // The return value may be false for ACTION_UP in
                        // Mapsforge even though our tap listener commits
                        // the destination; assert destination below.
                    }finally{
                        down.recycle()
                        up.recycle()
                    }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity{activity->
                    val actual=State.navigationDestination
                        ?:error("Map tap did not choose destination #${index+1}")
                    assertTrue("Each tap must choose a unique point",
                        visited.add(actual))
                    if(index%2==0){
                        val button=findButton(activity.window.decorView,
                            "Маршрут")?:error("No route button")
                        button.performClick()
                    }
                    val routeField=activity.javaClass.getDeclaredField("route")
                    routeField.isAccessible=true
                    val route=routeField.get(activity) as? RoutingEngine.Route
                    assertNotNull("No route for tap #${index+1}",route)
                    assertEquals(actual.first,route!!.destination.lat,0.0000001)
                    assertEquals(actual.second,route.destination.lon,0.0000001)

                    // The destination pin must land at the actual touch
                    // position even when map center Y is 0.68.
                    val screen=activity.javaClass.getDeclaredMethod(
                        "screenPoint",RoutingEngine.Point::class.java)
                    screen.isAccessible=true
                    @Suppress("UNCHECKED_CAST")
                    val screenPos=screen.invoke(activity,route.destination)
                        as Pair<Float,Float>
                    assertEquals("Pin X differs from touch",tapX,screenPos.first,3.0f)
                    assertEquals("Pin Y differs from touch",tapY,screenPos.second,3.0f)
                }
            }
            assertEquals(5,visited.size)
        } finally {
            scenario.close()
            State.navFix=null
            State.manualStart=null
            State.navigationDestination=null
        }
    }
    @Test fun threeLiveRoutesThroughProductionValhallaService() {
        val source=RoutingEngine.Point(50.4501,30.5234)
        val destinations=listOf(
            RoutingEngine.Point(50.4563,30.5355),
            RoutingEngine.Point(50.4443,30.5112),
            RoutingEngine.Point(50.4612,30.5054)
        )
        val engine=RoutingEngine()
        for((index,target) in destinations.withIndex()){
            val latch=CountDownLatch(1)
            var routeResult:Result<RoutingEngine.Route>?=null
            engine.request(source,target){result->
                routeResult=result
                latch.countDown()
            }
            assertTrue("Live routing request #${index+1} timed out",
                latch.await(35,TimeUnit.SECONDS))
            val route=routeResult?:error("No live routing result #${index+1}")
            assertTrue("Live route #${index+1} failed: "+
                route.exceptionOrNull()?.message,route.isSuccess)
            assertTrue("Route #${index+1} missing road geometry",
                route.getOrThrow().points.size>=2)
            assertEquals(target.lat,route.getOrThrow().destination.lat,0.00001)
            assertEquals(target.lon,route.getOrThrow().destination.lon,0.00001)
        }
    }

}
