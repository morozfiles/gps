package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SmoothCameraMathTest {
    @Test fun angleInterpolatesInSmallFramesInsteadOfFourteenHzJumps() {
        var heading=0.0
        val recorded=ArrayList<Double>()
        repeat(30) {
            val next=SmoothCameraMath.bearing(heading,90.0,0.033)
            val step=abs(SmoothCameraMath.bearing(heading,next,0.033,
                0.01,500.0)-heading)
            recorded.add(abs(((next-heading+540)%360)-180))
            heading=next
        }
        assertTrue(recorded.maxOrNull()!!<7.1)
        assertTrue(heading>75.0)
        assertTrue(heading<=90.0)
    }

    @Test fun angleWrapsAtNorthWithout360DegreeSpin() {
        var heading=356.0
        repeat(20){heading=SmoothCameraMath.bearing(heading,4.0,0.033)}
        assertTrue(heading in 0.0..5.0)
        assertEquals(356.0,SmoothCameraMath.bearing(356.0,356.0,0.033),0.01)
    }

    @Test fun easingRespondsToElapsedTimeNotGpsPollingPeriod() {
        val little=SmoothCameraMath.alpha(0.016,0.16)
        val medium=SmoothCameraMath.alpha(0.033,0.16)
        assertTrue(little>0.0 && medium>little && medium<0.5)
        val zoom=SmoothCameraMath.value(17.0,18.0,0.033,0.36)
        assertTrue(zoom>17.0 && zoom<17.2)
    }

    @Test fun unchangedCameraDoesNotTriggerUnnecessaryRedraw() {
        assertFalse(SmoothCameraMath.hasVisibleChange(0.01,0.01,0.001,0.01))
        assertTrue(SmoothCameraMath.hasVisibleChange(0.20,0.0,0.0,0.0))
        assertTrue(SmoothCameraMath.hasVisibleChange(0.0,0.35,0.0,0.0))
    }
}
