package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class ObdSpeedCalibrationTest {
    @Test fun defaultIs100PercentOfRawElmSpeed() {
        assertEquals(1.0,ObdSpeedCalibration.DEFAULT_FACTOR,0.000001)
        assertEquals(100.0,ObdSpeedCalibration.correctedKmh(100)!!,0.00001)
        assertEquals(50.0,ObdSpeedCalibration.correctedKmh(50)!!,0.00001)
    }
    @Test fun savedUserSettingIsHonoredInsteadOfNewDefault() {
        assertEquals(97.0,ObdSpeedCalibration.correctedKmh(100,0.97)!!,0.00001)
        assertEquals(102.0,ObdSpeedCalibration.correctedKmh(100,1.02)!!,0.00001)
        assertNull(ObdSpeedCalibration.correctedKmh(null,0.98))
    }
    @Test fun correctedSpeedFeedsDistanceInKilometers() {
        val kmh=ObdSpeedCalibration.correctedKmh(100)!!
        val elapsedSeconds=360.0
        assertEquals(10000.0,kmh/3.6*elapsedSeconds,0.01)
    }
}
