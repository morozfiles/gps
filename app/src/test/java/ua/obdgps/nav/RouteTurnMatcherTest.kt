package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class RouteTurnMatcherTest {
    private val route=listOf(
        RoutingEngine.Point(50.0,30.0),
        RoutingEngine.Point(50.0,30.00025),
        RoutingEngine.Point(50.0,30.00050),
        RoutingEngine.Point(50.0,30.00075),
        RoutingEngine.Point(50.0,30.00100),
        RoutingEngine.Point(50.00020,30.00100),
        RoutingEngine.Point(50.00040,30.00100),
        RoutingEngine.Point(50.00060,30.00100)
    )
    @Test fun geometryFindsTurnEvenWithNoGoogleManeuvers() {
        val bends=RouteTurnMatcher.findBends(route)
        assertTrue(bends.isNotEmpty())
        assertTrue(bends.any{it.signedAngleDeg < -33.0})
    }
    @Test fun actualGyroTurnMatchesNearbyCurveDespiteObdDistanceError() {
        val bend=RouteTurnMatcher.findBends(route).first()
        assertEquals(bend,RouteTurnMatcher.choose(listOf(bend),
            bend.meters-65.0,-22.0))
        assertEquals(bend,RouteTurnMatcher.choose(listOf(bend),
            bend.meters+55.0,-24.0))
        assertNull(RouteTurnMatcher.choose(listOf(bend),
            bend.meters-65.0,25.0))
        assertNull(RouteTurnMatcher.choose(listOf(bend),
            bend.meters-65.0,-9.0))
        assertNull(RouteTurnMatcher.choose(listOf(bend),
            bend.meters-65.0,-22.0,bend.vertex))
    }
    @Test fun nearerPastTurnWinsOverFartherFutureTurn() {
        val bends=listOf(
            RouteTurnMatcher.Bend(11,410.0,-85.0),
            RouteTurnMatcher.Bend(22,565.0,-88.0))
        // Reported OBD progress 475m: past bend at 410m (65m behind)
        // is closer than upcoming 565m (90m ahead).
        assertEquals(bends[0],RouteTurnMatcher.choose(bends,475.0,-70.0))
        // Reported progress 520m: upcoming bend 45m away is closer.
        assertEquals(bends[1],RouteTurnMatcher.choose(bends,520.0,-70.0))
        // Works even if the real turn happened 110m behind the odometer.
        assertEquals(bends[0],RouteTurnMatcher.choose(bends,520.0,-70.0,
            bends[1].vertex))
    }
    @Test fun pastTurnBeyondSafetyWindowIsNotMatched() {
        val bend=RouteTurnMatcher.Bend(12,240.0,80.0)
        assertNull(RouteTurnMatcher.choose(listOf(bend),400.0,70.0))
    }
    @Test fun gradualSnapNeverTeleports() {
        val x=RouteTurnMatcher.correctionStep(80.0,0.35)
        val y=RouteTurnMatcher.correctionStep(-70.0,0.35)
        assertTrue(x>0.0 && x<=5.0)
        assertTrue(y<0.0 && y>=-5.0)
        assertTrue(abs(RouteTurnMatcher.correctionStep(0.2,0.35))<0.01)
    }
}
