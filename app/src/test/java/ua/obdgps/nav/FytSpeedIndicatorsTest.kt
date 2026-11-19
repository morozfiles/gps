package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class FytSpeedIndicatorsTest {
    @Test fun gpsAndObdRequireFreshPositiveTimestamps(){
        assertFalse(FytSpeedIndicators.gpsStatus(50.0,0,100).valid)
        assertFalse(FytSpeedIndicators.obdStatus(25.0,0,100).valid)
        assertTrue(FytSpeedIndicators.gpsStatus(50.0,100,110).valid)
        assertTrue(FytSpeedIndicators.obdStatus(25.0,100,110).valid)
        assertFalse(FytSpeedIndicators.gpsStatus(50.0,100,6200).valid)
        assertFalse(FytSpeedIndicators.obdStatus(25.0,100,4700).valid)
        assertFalse(FytSpeedIndicators.gpsStatus(50.0,110,100).valid)
    }
    @Test fun zeroSpeedIsValidWhenStationaryButNotWhenStale(){
        val stopped=FytSpeedIndicators.obdStatus(0.0,10000,10100)
        assertTrue(stopped.valid)
        assertEquals(0.0,stopped.speedKmh?:-1.0,0.0001)
        assertTrue(FytSpeedIndicators.gpsStatus(0.0,10000,10100).valid)
        assertFalse(FytSpeedIndicators.obdStatus(0.0,10000,16000).valid)
    }
    @Test fun rejectsInvalidSpeedAndImplausibleValues(){
        for(value in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,-1.0,181.0)){
            assertFalse(FytSpeedIndicators.gpsStatus(value,10000,10200).valid)
        }
        for(value in listOf(null,Double.NaN,Double.NEGATIVE_INFINITY,
                -1.0,261.0)){
            assertFalse(FytSpeedIndicators.obdStatus(value,10000,10200).valid)
        }
    }
}
