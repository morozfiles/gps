package ua.obdgps.nav

import kotlin.math.*

/**
 * Camera-only helpers for Google map guidance. These functions must never
 * alter the raw GNSS fix, network observations, or inertial state.
 */
internal object NavigationCameraMath {
    fun normalizeBearing(degrees:Double):Double =
        ((degrees%360.0)+360.0)%360.0

    fun smoothBearing(previous:Double?,heading:Double?,stepDegrees:Double=35.0):Double? {
        if(heading==null || !heading.isFinite())return previous
        if(previous==null || !previous.isFinite())return normalizeBearing(heading)
        val delta=((heading-previous+540.0)%360.0)-180.0
        return normalizeBearing(previous+delta.coerceIn(-stepDegrees,stepDegrees))
    }

    fun zoom(speedKmh:Double?,turnMeters:Double?):Float {
        val speed=(speedKmh?:0.0).takeIf{it.isFinite()}?.coerceIn(0.0,180.0)?:0.0
        // FYT screen: stay close to the road. At 60 km/h use zoom 17.4
        // (previously 16.2), and never zoom out to a region-wide view.
        val normal=when {
            speed<8.0 -> 18.3
            speed<30.0 -> 18.3-(speed-8.0)/22.0*0.55
            speed<60.0 -> 17.75-(speed-30.0)/30.0*0.35
            speed<100.0 -> 17.4-(speed-60.0)/40.0*0.5
            else -> 16.9-(speed-100.0)/80.0*0.35
        }
        val turnBoost=if(turnMeters!=null && turnMeters.isFinite() &&
            turnMeters in 0.0..250.0 && speed>=10.0) {
            0.55*(1.0-turnMeters/250.0)
        }else 0.0
        return (normal+turnBoost).coerceIn(16.55,18.8).toFloat()
    }

    fun tilt(speedKmh:Double?):Float {
        val speed=(speedKmh?:0.0).takeIf{it.isFinite()}?.coerceIn(0.0,180.0)?:0.0
        // More road perspective, reaching 60 degrees at 60 km/h.
        // Google Maps may limit the actual angle for a given zoom level.
        val angle=when {
            speed<8.0 -> 35.0+speed/8.0*10.0
            speed<60.0 -> 45.0+(speed-8.0)/52.0*15.0
            else -> 60.0+(speed-60.0)/120.0*5.0
        }
        return angle.coerceIn(35.0,65.0).toFloat()
    }

    fun lookAheadMeters(speedKmh:Double?):Double {
        val speed=(speedKmh?:0.0).takeIf{it.isFinite()}?.coerceIn(0.0,180.0)?:0.0
        return (28.0+speed/3.6*2.6).coerceIn(28.0,158.0)
    }

    /** Find a point forward on the *selected* route, never on arbitrary roads. */
    fun routeLookAhead(route:List<RoutingEngine.Point>,segmentIndex:Int,
                       position:RoutingEngine.Point,distanceMeters:Double):RoutingEngine.Point {
        if(route.size<2)return position
        var remaining=distanceMeters.coerceIn(0.0,250.0)
        var previous=position
        val from=segmentIndex.coerceIn(0,route.size-2)
        for(i in from+1 until route.size) {
            val next=route[i]
            val leg=RoutingEngine.meters(previous,next)
            if(leg>0.01) {
                if(leg>=remaining) {
                    val fraction=(remaining/leg).coerceIn(0.0,1.0)
                    return RoutingEngine.Point(previous.lat+(next.lat-previous.lat)*fraction,
                        previous.lon+(next.lon-previous.lon)*fraction)
                }
                remaining-=leg
            }
            previous=next
        }
        return route.last()
    }
}
