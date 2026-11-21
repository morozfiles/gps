package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class MobileDrAutoStartTest {
    @Test fun routeAutostartsWithLiveObdAfterGpsLossEvenWithoutAirAlert(){
        assertTrue(MobileDrAutoStart.eligible(
            autoEnabled=true,alreadyDr=false,routeActive=true,
            alertActive=false,freshObd=true,obdKmh=32.0,badGps=true))
    }
    @Test fun stationaryOrNoObdMustNotStart(){
        for(speed in listOf(0.0,1.0,2.49,Double.NaN)){
            assertFalse(MobileDrAutoStart.eligible(
                true,false,true,false,true,speed,true))
        }
        assertFalse(MobileDrAutoStart.eligible(
            true,false,true,false,false,55.0,true))
    }
    @Test fun reliableGpsAndUserDisabledAutoMustNotSwitch(){
        assertFalse(MobileDrAutoStart.eligible(
            true,false,true,false,true,55.0,false))
        assertFalse(MobileDrAutoStart.eligible(
            false,false,true,false,true,55.0,true))
        assertFalse(MobileDrAutoStart.eligible(
            true,true,true,false,true,55.0,true))
    }
    @Test fun alertCanTriggerWithoutRouteButNeedsHigherSpeed(){
        assertTrue(MobileDrAutoStart.eligible(
            true,false,false,true,true,28.0,true))
        assertFalse(MobileDrAutoStart.eligible(
            true,false,false,true,true,5.0,true))
        assertFalse(MobileDrAutoStart.eligible(
            true,false,false,false,true,28.0,true))
    }
    @Test fun gpsLossDebounceAndTeleportRecoveryAreDeterministic(){
        assertEquals(2500L,MobileDrAutoStart.debounceMs(false))
        assertEquals(1200L,MobileDrAutoStart.debounceMs(true))
    }
    @Test fun seededDrMovesUsingObdOnlyWithNoGpsProvider(){
        val dr=DeadReckoner()
        dr.seed(50.4501,30.5234,90.0)
        val initial=dr.pose!!
        repeat(10){dr.move(36.0,1.0)}
        val after=dr.pose!!
        assertTrue("OBD-only DR should advance east when bearing is 90",
            after.lon>initial.lon)
        assertTrue("100m of 36km/h travel must move longitude",
            after.lon-initial.lon>0.001)
        dr.turn(0.2,0.4)
        assertNotEquals("Gyroscope yaw should change route heading",
            after.bearing,dr.pose!!.bearing,0.0001)
    }
}
