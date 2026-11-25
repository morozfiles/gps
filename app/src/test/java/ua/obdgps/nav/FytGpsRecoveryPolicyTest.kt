package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class FytGpsRecoveryPolicyTest {
    @Test fun returningGpsNearDrNeedsShortStableWindow(){
        val evidence=FytGpsRecoveryPolicy.evaluate(
            40.0,30.0,null,null,30.0,true,true)
        assertEquals(FytGpsRecoveryPolicy.Evidence.INDEPENDENT,evidence)
        assertEquals(5000L,FytGpsRecoveryPolicy.requiredWindowMs(evidence))
    }

    @Test fun drDriftCanBeOverKilometerIfRouteAndObdCorroborate(){
        val evidence=FytGpsRecoveryPolicy.evaluate(
            1300.0,600.0,null,null,20.0,true,true)
        assertEquals(FytGpsRecoveryPolicy.Evidence.ROUTE_WITH_MOTION,evidence)
        assertEquals(12000L,FytGpsRecoveryPolicy.requiredWindowMs(evidence))
        assertTrue(FytGpsRecoveryPolicy.requiredSamples(evidence)>=9)
    }

    @Test fun routeAloneOrBadSpeedNeverReleasesQuarantine(){
        val t=FytGpsRecoveryPolicy.Evidence.REJECTED
        assertEquals(t,FytGpsRecoveryPolicy.evaluate(
            1200.0,500.0,null,null,10.0,false,true))
        assertEquals(t,FytGpsRecoveryPolicy.evaluate(
            1200.0,500.0,null,null,10.0,true,false))
        assertEquals(t,FytGpsRecoveryPolicy.evaluate(
            1200.0,500.0,null,null,300.0,true,true))
    }

    @Test fun contradictoryWifiOverridesRoutePlausibility(){
        assertEquals(FytGpsRecoveryPolicy.Evidence.REJECTED,
            FytGpsRecoveryPolicy.evaluate(
                1800.0,500.0,600.0,40.0,25.0,true,true))
    }

    @Test fun stationarySpoofMustNotPassLongRouteOnlyRecovery(){
        assertFalse(FytGpsRecoveryPolicy.routeMotionEnough(0.0,55.0,12000L))
        assertFalse(FytGpsRecoveryPolicy.routeMotionEnough(12.0,55.0,12000L))
        assertTrue(FytGpsRecoveryPolicy.routeMotionEnough(55.0,55.0,12000L))
        assertFalse(FytGpsRecoveryPolicy.routeMotionEnough(55.0,55.0,5000L))
    }

    @Test fun successiveFixesMustNotJumpAcrossCity(){
        assertTrue(FytGpsRecoveryPolicy.consecutivePlausible(
            25.0,1.0,45.0,7.0))
        assertFalse(FytGpsRecoveryPolicy.consecutivePlausible(
            1500.0,1.0,40.0,8.0))
    }
}
