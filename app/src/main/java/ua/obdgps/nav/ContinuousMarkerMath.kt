package ua.obdgps.nav

import kotlin.math.*

/** Smooth display-only motion; no provider coordinates are modified. */
internal object ContinuousMarkerMath {
    fun forward(p:RoutingEngine.Point,bearingDeg:Double,
                speedKmh:Double,seconds:Double):RoutingEngine.Point {
        if(!bearingDeg.isFinite()||!speedKmh.isFinite()||!seconds.isFinite())
            return p
        val meters=(speedKmh.coerceIn(0.0,180.0)/3.6*
            seconds.coerceIn(0.0,0.48)).coerceIn(0.0,24.0)
        val radians=Math.toRadians(bearingDeg)
        val lat=p.lat+meters*cos(radians)/111195.0
        val safeCos=cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)
        val lon=p.lon+meters*sin(radians)/(111195.0*safeCos)
        return RoutingEngine.Point(lat,lon)
    }
    fun followFactor(frameSeconds:Double):Double {
        if(!frameSeconds.isFinite()||frameSeconds<=0)return 0.0
        return (1.0-exp(-frameSeconds.coerceAtMost(0.1)/0.082))
            .coerceIn(0.0,1.0)
    }
    fun angleDelta(from:Double,to:Double):Double =
        ((to-from+540.0)%360.0)-180.0
    fun smoothAngle(from:Double,to:Double,factor:Double):Double =
        ((from+angleDelta(from,to)*factor.coerceIn(0.0,1.0))%360.0+360.0)%360.0
}
