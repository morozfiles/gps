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

    @Test fun remainingAtFinishStaysAtDestination() {
        val remaining=RouteDisplayGeometry.remaining(route,100000.0)
        assertEquals(destination,remaining.first())
        assertEquals(destination,remaining.last())
        assertTrue(remaining.size>=2)
    }
}
