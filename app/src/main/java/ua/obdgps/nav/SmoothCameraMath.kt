package ua.obdgps.nav

import kotlin.math.*

/**
 * Time-based, bounded camera interpolation. Updates at the display cadence,
 * not when the GPS or OBD packet arrives. Pure display math only.
 */
internal object SmoothCameraMath {
    const val FRAME_INTERVAL_MS=33L

    fun alpha(dtSeconds:Double,responseSeconds:Double):Double {
        if(!dtSeconds.isFinite() || dtSeconds<=0.0 ||
            !responseSeconds.isFinite() || responseSeconds<=0.0)return 0.0
        return (1.0-exp(-dtSeconds.coerceAtMost(0.12)/responseSeconds))
            .coerceIn(0.0,1.0)
    }

    fun value(current:Double,target:Double,dtSeconds:Double,
              responseSeconds:Double):Double {
        if(!current.isFinite() || !target.isFinite())return current
        return current+(target-current)*alpha(dtSeconds,responseSeconds)
    }

    fun bearing(current:Double,target:Double,dtSeconds:Double,
                responseSeconds:Double=0.18,
                maxDegreesPerSecond:Double=210.0):Double {
        if(!current.isFinite() || !target.isFinite())return current
        if(!dtSeconds.isFinite() || dtSeconds<=0.0)return normalize(current)
        val error=((target-current+540.0)%360.0)-180.0
        val limited=error*alpha(dtSeconds,responseSeconds)
        val limit=maxDegreesPerSecond.coerceIn(1.0,500.0)*
            dtSeconds.coerceIn(0.0,0.12)
        return normalize(current+limited.coerceIn(-limit,limit))
    }

    fun normalize(value:Double):Double=((value%360.0)+360.0)%360.0

    fun hasVisibleChange(meters:Double,rotationDegrees:Double,
                         zoomDelta:Double,tiltDelta:Double):Boolean =
        (meters.isFinite() && meters>=0.10) ||
            (rotationDegrees.isFinite() && abs(rotationDegrees)>=0.08) ||
            (zoomDelta.isFinite() && abs(zoomDelta)>=0.003) ||
            (tiltDelta.isFinite() && abs(tiltDelta)>=0.05)
}
