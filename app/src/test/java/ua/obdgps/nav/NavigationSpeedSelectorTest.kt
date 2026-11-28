package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class NavigationSpeedSelectorTest {
    @Test fun trustedGpsWinsInGpsMode() {
        assertEquals(SpeedDisplay(62.0,"GPS"),
            NavigationSpeedSelector.choose(false,true,62.0,57.0))
    }
    @Test fun obdWinsInInertialMode() {
        assertEquals(SpeedDisplay(57.0,"OBD"),
            NavigationSpeedSelector.choose(true,true,62.0,57.0))
    }
    @Test fun ignoresSpoofedGpsAndFallsBackToObd() {
        assertEquals(SpeedDisplay(39.0,"OBD"),
            NavigationSpeedSelector.choose(false,false,88.0,39.0))
    }
    @Test fun neverUsesStaleOrUntrustedGpsAlone() {
        assertEquals(SpeedDisplay(null,"НЕТ ДАННЫХ"),
            NavigationSpeedSelector.choose(true,false,88.0,null))
    }
    @Test fun rejectsInvalidMeasurements() {
        assertEquals(SpeedDisplay(null,"НЕТ ДАННЫХ"),
            NavigationSpeedSelector.choose(false,true,Double.NaN,-5.0))
        assertEquals(SpeedDisplay(null,"НЕТ ДАННЫХ"),
            NavigationSpeedSelector.choose(false,true,201.0,null))
    }
    @Test fun movingObdImmediatelyOverridesZeroGps() {
        assertEquals(SpeedDisplay(1.0,"OBD"),
            NavigationSpeedSelector.choose(false,true,0.0,1.0))
        assertTrue(FytGpsFailover.shouldUseObd(0.0,1.0,0))
        assertTrue(FytGpsFailover.shouldUseObd(null,20.0,0))
    }
    @Test fun gpsSilenceUsesActualFixAge() {
        assertFalse(FytGpsFailover.shouldUseObd(20.0,20.0,2500))
        assertTrue(FytGpsFailover.shouldUseObd(20.0,20.0,2501))
        assertFalse(FytGpsFailover.shouldUseObd(0.0,0.0,3000))
        assertFalse(FytGpsFailover.shouldUseObd(null,null,3000))
    }
    @Test fun stoppedVehicleDisplaysZero() {
        assertEquals(SpeedDisplay(0.0,"OBD"),
            NavigationSpeedSelector.choose(true,false,null,0.0))
    }
}
