package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class SensorCalibrationCardsTest {
    private val allSensors=SensorCalibrationSnapshot(
        gyroAvailable=true,
        accelAvailable=true,
        linearAccelAvailable=true,
        gravityAvailable=true,
        compassAvailable=true,
        gyroFresh=true,
        accelFresh=true,
        compassFresh=true,
        compassReliable=true,
        yawDegreesPerSecond=0.12,
        accelerationMetersPerSecond2=0.08,
        headingDegrees=245.0,
        fieldMicroTesla=46.0
    )

    @Test fun detectedSensorsMustNeverBeMarkedAsCalibrated(){
        for(sensor in CalibrationSensor.values()){
            assertFalse(SensorCalibrationCards.checked(sensor,allSensors))
            assertFalse(SensorCalibrationCards.buttonText(sensor,allSensors).contains("✓"))
        }
    }

    @Test fun bothBiasesAndAvailableHardwareAreRequiredForBothStationaryCards(){
        for(kind in listOf(CalibrationSensor.GYROSCOPE,
            CalibrationSensor.ACCELEROMETER)){
            assertFalse(SensorCalibrationCards.checked(kind,
                allSensors.copy(gyroCalibrated=true)))
            assertFalse(SensorCalibrationCards.checked(kind,
                allSensors.copy(accelCalibrated=true)))
            assertFalse(SensorCalibrationCards.checked(kind,
                allSensors.copy(gyroCalibrated=true,accelCalibrated=true,
                    linearAccelAvailable=false)))
            assertFalse(SensorCalibrationCards.checked(kind,
                allSensors.copy(gyroCalibrated=true,accelCalibrated=true,
                    gravityAvailable=false)))
            assertFalse(SensorCalibrationCards.checked(kind,
                allSensors.copy(gyroCalibrated=true,accelCalibrated=true,
                    calibrationRunning=true)))
            val passed=allSensors.copy(gyroCalibrated=true,accelCalibrated=true)
            assertTrue(SensorCalibrationCards.checked(kind,passed))
            assertTrue(SensorCalibrationCards.buttonText(kind,passed).contains("✓"))
        }
    }

    @Test fun compassRequiresEveryGateIncludingAlignmentAndLiveQuality(){
        val kind=CalibrationSensor.COMPASS
        assertFalse(SensorCalibrationCards.checked(kind,
            allSensors.copy(compassCheckPassed=true)))
        assertFalse(SensorCalibrationCards.checked(kind,
            allSensors.copy(compassAligned=true)))
        val complete=allSensors.copy(compassCheckPassed=true,compassAligned=true)
        assertTrue(SensorCalibrationCards.checked(kind,complete))
        assertFalse(SensorCalibrationCards.checked(kind,
            complete.copy(compassReliable=false)))
        assertFalse(SensorCalibrationCards.checked(kind,
            complete.copy(compassFresh=false)))
        assertFalse(SensorCalibrationCards.checked(kind,
            complete.copy(compassChecking=true)))
        assertFalse(SensorCalibrationCards.checked(kind,
            complete.copy(compassAvailable=false)))
    }

    @Test fun labelsContainLiveNumbersAndNotFalseCheckmarks(){
        assertEquals("Компас\n245°\n46 мкТ",
            SensorCalibrationCards.buttonText(CalibrationSensor.COMPASS,allSensors))
        assertTrue(SensorCalibrationCards.buttonText(
            CalibrationSensor.GYROSCOPE,allSensors).contains("0.1°/с"))
        assertTrue(SensorCalibrationCards.buttonText(
            CalibrationSensor.ACCELEROMETER,allSensors).contains("0.1 м/с²"))
    }

    @Test fun guidanceExplainsFailuresAndExistingPhysicalRequirements(){
        val gyro=SensorCalibrationCards.hint(CalibrationSensor.GYROSCOPE,
            allSensors.copy(calibrationMessage="Мало данных: повтори калибровку"))
        assertTrue(gyro.contains("40+"))
        assertTrue(gyro.contains("20+"))
        assertTrue(gyro.contains("Мало данных"))
        val compass=SensorCalibrationCards.hint(CalibrationSensor.COMPASS,
            allSensors.copy(compassCheckPassed=true,compassAligned=false))
        assertTrue(compass.contains("4 устойчивых"))
        assertTrue(compass.contains("15 км/ч"))
    }
}
