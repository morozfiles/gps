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
    @Test fun stoppedVehicleDisplaysZero() {
        assertEquals(SpeedDisplay(0.0,"OBD"),
            NavigationSpeedSelector.choose(true,false,null,0.0))
    }
}
