package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class FytSourcePriorityTest {
    @Test fun externalIsAlwaysInspected() {
        assertTrue(FytGpsPriority.process(true,1000L,1300L))
    }
    @Test fun internalYieldsToRecentlyAcceptedExternal() {
        assertFalse(FytGpsPriority.process(false,1000L,3600L))
        assertFalse(FytGpsPriority.process(false,1000L,5500L))
    }
    @Test fun internalTakesOverWhenExternalExpires() {
        assertTrue(FytGpsPriority.process(false,1000L,5501L))
        assertTrue(FytGpsPriority.process(false,0L,1000L))
        assertTrue(FytGpsPriority.process(false,9999L,8000L))
    }
    @Test fun gyroMountAxisYIsDefaultForUprightDisplay(){
        val sample=floatArrayOf(0.2f,0.5f,-0.3f)
        assertEquals(0.5,FytGyroAxis.project(sample,"Y+"),0.0001)
        assertEquals(-0.5,FytGyroAxis.project(sample,"Y-"),0.0001)
        assertEquals(0.2,FytGyroAxis.project(sample,"X+"),0.0001)
        assertEquals(0.3,FytGyroAxis.project(sample,"Z-"),0.0001)
    }
}
