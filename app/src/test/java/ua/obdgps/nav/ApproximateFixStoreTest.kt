package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class ApproximateFixStoreTest {
    private fun fix(lat:Double=50.45,lon:Double=30.52,
        accuracy:Float=1200f,time:Long=97_000L)=
        State.NavFix(lat,lon,null,accuracy,
            "Примерно · Wi-Fi/сеть",time)

    @Test fun acceptsVeryApproximateFreshCellTowerPosition() {
        assertTrue(ApproximateFixStore.usable(fix(accuracy=18000f),100_000L))
        assertTrue(ApproximateFixStore.usable(fix(accuracy=90000f),100_000L))
    }

    @Test fun rejectsStaleOrImpossiblePosition() {
        assertFalse(ApproximateFixStore.usable(fix(time=1_000L),700_000L))
        assertFalse(ApproximateFixStore.usable(fix(accuracy=0f),100_000L))
        assertFalse(ApproximateFixStore.usable(fix(accuracy=150000f),100_000L))
        assertFalse(ApproximateFixStore.usable(fix(lat=95.0),100_000L))
    }

    @Test fun rejectsKnownSpoofingLimaPosition() {
        assertFalse(ApproximateFixStore.usable(
            fix(lat=-12.04,lon=-77.03),100_000L))
    }
}
