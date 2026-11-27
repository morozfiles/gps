package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class GuidanceCameraAnchorTest {
    @Test fun carIsLowOnMapWithoutOverlappingFytNotices(){
        val h=720
        val noticesStart=545
        val clearance=65
        val y=GuidanceCameraAnchor.markerY(h,noticesStart,clearance)
        assertTrue(y>h/2)
        assertTrue(y<=noticesStart-clearance)
        assertEquals(480,y)
    }

    @Test fun arrowUsesExtraSpaceWhenNoticeBannerAllows(){
        assertEquals(888,GuidanceCameraAnchor.markerY(1200,1000,50))
    }

    @Test fun shorterScreenKeepsVehicleAboveNoticeBanner(){
        val y=GuidanceCameraAnchor.markerY(600,425,62)
        assertEquals(363,y)
        assertTrue(y<425-45)
    }

    @Test fun cameraDoesNotMoveWhenVehicleIsAlreadyAtAnchor(){
        val current=RoutingEngine.Point(50.0,30.0)
        val marker=RoutingEngine.Point(50.001,30.001)
        assertEquals(current,GuidanceCameraAnchor.shiftedTarget(
            current,marker,marker))
    }

    @Test fun cameraMovesTowardVehicleDifferenceWithoutJump(){
        val current=RoutingEngine.Point(50.0,30.0)
        val marker=RoutingEngine.Point(50.0,30.003)
        val atAnchor=RoutingEngine.Point(50.0,30.002)
        val shifted=GuidanceCameraAnchor.shiftedTarget(current,marker,atAnchor)
        assertEquals(30.0003,shifted.lon,0.000001)
    }
}
