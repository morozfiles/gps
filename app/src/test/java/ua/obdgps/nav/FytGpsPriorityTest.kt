package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class FytGpsPriorityTest {
    @Test fun usbWinsOnlyWhileAnAcceptedFixIsFresh(){
        assertFalse(FytGpsPriority.externalPreferred(3000L,0L))
        assertTrue(FytGpsPriority.externalPreferred(50_000L,49_000L))
        assertTrue(FytGpsPriority.externalPreferred(50_000L,43_500L))
        assertFalse(FytGpsPriority.externalPreferred(50_001L,43_500L))
        assertFalse(FytGpsPriority.externalPreferred(50_000L,43_000L))
        assertFalse(FytGpsPriority.externalPreferred(43_000L,50_000L))
    }

    @Test fun duplicateFusedAndGpsSamplesAreNotIndependentFixes(){
        assertFalse(FytGpsPriority.duplicateMockNanos(1000L,0L))
        assertTrue(FytGpsPriority.duplicateMockNanos(
            10_150_000_000L,10_000_000_000L))
        assertFalse(FytGpsPriority.duplicateMockNanos(
            10_500_000_000L,10_000_000_000L))
    }
}
