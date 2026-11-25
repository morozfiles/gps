package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class NavigationCameraMathTest {
    @Test fun zoomMovesOutWithSpeed() {
        assertTrue(NavigationCameraMath.zoom(0.0,null) >
            NavigationCameraMath.zoom(55.0,null))
        assertTrue(NavigationCameraMath.zoom(55.0,null) >
            NavigationCameraMath.zoom(125.0,null))
    }

    @Test fun fyT60KmhKeepsStreetsCloseAndMoreTilted() {
        assertEquals(17.4f,NavigationCameraMath.zoom(60.0,null),0.02f)
        assertEquals(66.0f,NavigationCameraMath.tilt(60.0),0.1f)
        assertTrue(NavigationCameraMath.lookAheadMeters(60.0)>85.0)
        assertTrue(NavigationCameraMath.tilt(180.0)<=67.0f)
        assertTrue(NavigationCameraMath.zoom(100.0,null)>=16.85f)
        assertTrue(NavigationCameraMath.zoom(180.0,null)>=16.5f)
        assertTrue(NavigationCameraMath.tilt(100.0)>=60.0f)
        assertTrue(NavigationCameraMath.zoom(60.0,35.0)>
            NavigationCameraMath.zoom(60.0,null))
    }

    @Test fun approachingTurnBringsCameraCloser() {
        assertTrue(NavigationCameraMath.zoom(55.0,30.0) >
            NavigationCameraMath.zoom(55.0,1000.0))
    }

    @Test fun smoothHeadingCrossesNorthWithoutRotatingBackwards() {
        val x=NavigationCameraMath.smoothBearing(355.0,5.0)
        assertEquals(5.0,x!!,0.01)
        val y=NavigationCameraMath.smoothBearing(5.0,270.0,24.0)
        assertEquals(341.0,y!!,0.01)
    }

    @Test fun routeCameraLooksForwardOnSelectedRoad() {
        val route=listOf(
            RoutingEngine.Point(50.0,30.0),
            RoutingEngine.Point(50.0,30.001),
            RoutingEngine.Point(50.001,30.001))
        val p=NavigationCameraMath.routeLookAhead(route,0,route[0],150.0)
        assertEquals(30.001,p.lon,0.000001)
        assertTrue(p.lat>50.0)
        assertTrue(p.lat<50.001)
    }

    @Test fun lookAheadExpandsAtSpeed() {
        assertTrue(NavigationCameraMath.lookAheadMeters(10.0) <
            NavigationCameraMath.lookAheadMeters(110.0))
    }
}
