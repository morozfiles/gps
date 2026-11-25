package ua.obdgps.nav

import kotlin.math.*

/**
 * Recovery policy for FYT GNSS after spoofing/jamming. GPS continues to be
 * observed while the driving position is advanced using OBD+IMU.
 *
 * Route geometry alone is NOT sufficient evidence: require fresh OBD,
 * plausible speed, and a longer continuous time series before switching.
 */
internal object FytGpsRecoveryPolicy {
    enum class Evidence { REJECTED, INDEPENDENT, ROUTE_WITH_MOTION }
    const val GOOD_FIX_MAX_ACCURACY_METERS=50.0
    const val ROUTE_MAX_DISTANCE_METERS=115.0
    const val ROUTE_REQUIRED_MS=12_000L
    const val INDEPENDENT_REQUIRED_MS=5_000L

    fun evaluate(
        drGapMeters:Double?,
        driftMeters:Double,
        networkGapMeters:Double?,
        networkAccuracyMeters:Double?,
        routeGapMeters:Double?,
        freshObd:Boolean,
        speedConsistent:Boolean
    ):Evidence {
        if(!speedConsistent)return Evidence.REJECTED
        // A fresh independent network observation contradicting GNSS must
        // override an on-route candidate: GNSS can be spoofed onto a road.
        if(networkGapMeters!=null && networkGapMeters.isFinite()){
            val limit=((networkAccuracyMeters?:100.0)*2.0+75.0)
                .coerceIn(85.0,250.0)
            if(networkGapMeters>limit)return Evidence.REJECTED
            return Evidence.INDEPENDENT
        }
        if(drGapMeters!=null && drGapMeters.isFinite()){
            val limit=(65.0+driftMeters.coerceAtLeast(0.0)*1.5)
                .coerceIn(65.0,250.0)
            if(drGapMeters<=limit)return Evidence.INDEPENDENT
        }
        if(freshObd && routeGapMeters!=null &&
            routeGapMeters.isFinite() &&
            routeGapMeters in 0.0..ROUTE_MAX_DISTANCE_METERS)
            return Evidence.ROUTE_WITH_MOTION
        return Evidence.REJECTED
    }

    fun requiredWindowMs(evidence:Evidence):Long =
        if(evidence==Evidence.ROUTE_WITH_MOTION)ROUTE_REQUIRED_MS
        else INDEPENDENT_REQUIRED_MS

    fun requiredSamples(evidence:Evidence):Int =
        if(evidence==Evidence.ROUTE_WITH_MOTION)9 else 5

    /** A route-plausible but frozen spoofed coordinate cannot restore GPS while the car moves. */
    fun routeMotionEnough(travelMeters:Double,speedKmh:Double,stableMs:Long):Boolean {
        if(!travelMeters.isFinite() || !speedKmh.isFinite() ||
            stableMs<ROUTE_REQUIRED_MS)return false
        val expected=speedKmh.coerceIn(0.0,180.0)/3.6*(stableMs/1000.0)
        return travelMeters>=max(6.0,expected*0.16)
    }

    /** Compare consecutive candidates with actual vehicle speed (not the old GPS anchor). */
    fun consecutivePlausible(
        deltaMeters:Double,elapsedSeconds:Double,
        speedKmh:Double,accuracyMeters:Double
    ):Boolean {
        if(!deltaMeters.isFinite() || !elapsedSeconds.isFinite() ||
            elapsedSeconds<=0.0 || elapsedSeconds>5.0 ||
            !speedKmh.isFinite())return false
        val maxTravel=(speedKmh.coerceIn(0.0,180.0)/3.6*elapsedSeconds)
            +max(45.0,accuracyMeters*2.0+25.0)
        return deltaMeters<=maxTravel
    }
}
