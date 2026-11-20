package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class TrustedTurnCounterTest {
    @Test fun turnsAreCountedFromGpsWithoutAnyGyroscope() {
        val tracker=TrustedTurnCounter()
        val observed=mutableListOf<TrustedTurnCounter.Direction>()
        val bearings=listOf(0.0,0.0,9.0,20.0,32.0,46.0,60.0,75.0,
            88.0,90.0,90.0,90.0,90.0,90.0,90.0,
            78.0,65.0,52.0,39.0,27.0,15.0,3.0,0.0)
        bearings.forEachIndexed{idx,b->
            tracker.observe(b,1000L*(idx+1))?.let{observed.add(it)}
        }
        assertEquals(
            listOf(TrustedTurnCounter.Direction.RIGHT,
                TrustedTurnCounter.Direction.LEFT),
            observed
        )
    }
    @Test fun straightDrivingNeverGeneratesTurn() {
        val tracker=TrustedTurnCounter()
        repeat(60){i->
            assertNull(tracker.observe((i%3-1)*0.7,1000L*(i+1)))
        }
    }
    @Test fun oneOffLargeGpsBearingJumpMustNotCount() {
        val tracker=TrustedTurnCounter()
        val values=listOf(0.0,0.0,110.0,0.0,0.0,0.0)
        values.forEachIndexed{i,b->
            assertNull(tracker.observe(b,(i+1)*1000L))
        }
    }
    @Test fun courseWrapFrom350To20MeansRightTurn() {
        val tracker=TrustedTurnCounter()
        val values=listOf(350.0,353.0,360.0,9.0,20.0,31.0)
        val seen=values.mapIndexedNotNull{i,v->
            tracker.observe(v,(i+1)*1000L)
        }
        assertEquals(listOf(TrustedTurnCounter.Direction.RIGHT),seen)
    }
}
