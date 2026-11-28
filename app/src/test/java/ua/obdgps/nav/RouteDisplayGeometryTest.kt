package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class RouteDisplayGeometryTest {
    private val first=RoutingEngine.Point(50.0,30.0)
    private val corner=RoutingEngine.Point(50.0,30.01)
    private val destination=RoutingEngine.Point(50.01,30.01)
    private val route=listOf(first,corner,destination)

    @Test fun projectionRemovesAlreadyTraveledSegments() {
        val fix=RoutingEngine.Point(50.0,30.005)
        val p=RouteDisplayGeometry.project(route,fix)!!
        assertTrue(p.lateralMeters<1.0)
        assertTrue(p.alongMeters>300.0)
        val remaining=RouteDisplayGeometry.remaining(route,p.alongMeters)
        assertEquals(30.005,remaining.first().lon,0.00005)
        assertEquals(destination,remaining.last())
        assertFalse(remaining.contains(first))
    }

    @Test fun extrapolationFollowsUpcomingTurn() {
        val p=RouteDisplayGeometry.project(route,corner)!!
        val ahead=RouteDisplayGeometry.atDistance(route,p.alongMeters+100.0)
        assertTrue(ahead.lat>corner.lat)
        assertEquals(corner.lon,ahead.lon,0.000001)
    }

    @Test fun predictionDoesNotTurnBeforeObservedVehicle() {
        val beforeTurn=RoutingEngine.Point(50.0,30.00985)
        val ahead=RouteDisplayGeometry.predictAhead(
            route,beforeTurn,90.0,19.0)!!
        assertEquals(first.lat,ahead.lat,0.0000001)
        assertTrue(ahead.lon<corner.lon)
    }

    @Test fun confirmedGyroOrGpsHeadingCanStartTurnWithoutLag() {
        val beforeTurn=RoutingEngine.Point(50.0,30.00985)
        val gpsTurn=RouteDisplayGeometry.predictAhead(
            route,beforeTurn,0.0,19.0)!!
        assertTrue(gpsTurn.lat>corner.lat)
        val gyroTurn=RouteDisplayGeometry.predictAhead(
            route,beforeTurn,90.0,19.0,-25.0)!!
        assertTrue(gyroTurn.lat>corner.lat)
        val wrongYaw=RouteDisplayGeometry.predictAhead(
            route,beforeTurn,90.0,19.0,25.0)!!
        assertEquals(corner.lat,wrongYaw.lat,0.0000001)
    }

    @Test fun predictionNeverExtrapolatesFarAwayGpsAsRoutePosition() {
        val far=RoutingEngine.Point(50.015,30.015)
        assertNull(RouteDisplayGeometry.predictAhead(route,far,0.0,20.0))
    }

    @Test fun flyoverCrossingKeepsUntravelledRampUntilSecondPass() {
        val start=RoutingEngine.Point(50.0,29.999)
        val crossing=RoutingEngine.Point(50.0,30.0)
        val ramp=listOf(start,crossing,RoutingEngine.Point(50.0,30.001),
            RoutingEngine.Point(49.999,30.001),RoutingEngine.Point(49.999,30.0),
            crossing,RoutingEngine.Point(50.001,30.0))
        val lengths=RouteTurnMatcher.cumulative(ramp)
        val noisy=RoutingEngine.Point(50.00002,30.0)
        assertTrue(RouteDisplayGeometry.project(ramp,noisy)!!.alongMeters>lengths[4])
        val first=RouteDisplayGeometry.projectNear(ramp,noisy,lengths[1],35.0,35.0,90.0,lengths)!!
        assertEquals(lengths[1],first.alongMeters,4.0)
        val remaining=RouteDisplayGeometry.remaining(ramp,first.alongMeters)
        assertTrue(remaining.contains(ramp[3]))
        assertTrue(remaining.contains(ramp[4]))
        val second=RouteDisplayGeometry.projectNear(ramp,crossing,lengths[5],35.0,35.0,0.0,lengths)!!
        assertEquals(lengths[5],second.alongMeters,4.0)
        val planned=RoutingEngine.Route(ramp,emptyList(),lengths.last()/1000,120,ramp.last())
        assertTrue(planned.progressAt(first.alongMeters).remainingMeters>350.0)
        assertTrue(planned.progressAt(second.alongMeters).remainingMeters<120.0)
    }

    @Test fun shortLoopAtSameCoordinatePrefersExistingProgress() {
        val a=RoutingEngine.Point(50.0,30.0)
        val points=listOf(a,RoutingEngine.Point(50.0,30.00008),
            RoutingEngine.Point(50.00006,30.00008),a,
            RoutingEngine.Point(50.0,30.00015))
        val lengths=RouteTurnMatcher.cumulative(points)
        val first=RouteDisplayGeometry.projectNear(points,a,0.0,35.0,35.0,90.0,lengths)!!
        assertEquals(0.0,first.alongMeters,0.01)
        val later=RouteDisplayGeometry.projectNear(points,a,lengths[3],35.0,35.0,90.0,lengths)!!
        assertEquals(lengths[3],later.alongMeters,0.01)
    }

    @Test fun remainingAtFinishStaysAtDestination() {
        val remaining=RouteDisplayGeometry.remaining(route,100000.0)
        assertEquals(destination,remaining.first())
        assertEquals(destination,remaining.last())
        assertTrue(remaining.size>=2)
    }
}
