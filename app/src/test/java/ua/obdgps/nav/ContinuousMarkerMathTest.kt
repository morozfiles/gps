package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class ContinuousMarkerMathTest {
    @Test fun movementIsContinuousBetweenOneSecondGpsUpdates(){
        val p=RoutingEngine.Point(50.0,30.0)
        val a=ContinuousMarkerMath.forward(p,90.0,60.0,0.1)
        val b=ContinuousMarkerMath.forward(p,90.0,60.0,0.2)
        val c=ContinuousMarkerMath.forward(p,90.0,60.0,0.3)
        assertTrue(a.lon>p.lon)
        assertTrue(b.lon>a.lon)
        assertTrue(c.lon>b.lon)
        assertEquals(p.lat,c.lat,0.000001)
    }
    @Test fun preventsLongGpsSpoofProjection(){
        val p=RoutingEngine.Point(50.0,30.0)
        val predicted=ContinuousMarkerMath.forward(p,90.0,180.0,10.0)
        assertTrue(RoutingEngine.meters(p,predicted)<25.0)
    }
    @Test fun smoothingAndRotationDoNotJumpAtNorth(){
        assertTrue(ContinuousMarkerMath.followFactor(0.033)>0.0)
        assertTrue(ContinuousMarkerMath.followFactor(0.033)<0.5)
        assertEquals(0.0,ContinuousMarkerMath.smoothAngle(350.0,10.0,0.5),0.01)
    }
}
