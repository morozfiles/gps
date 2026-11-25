package ua.obdgps.nav

import kotlin.math.*

/** Pure display geometry, never used as a replacement GPS or DR location. */
internal object RouteDisplayGeometry {
    data class Projection(val alongMeters:Double,val lateralMeters:Double)

    fun project(points:List<RoutingEngine.Point>,
                position:RoutingEngine.Point):Projection? {
        if(points.size<2)return null
        val latScale=111195.0
        val lonScale=latScale*cos(Math.toRadians(position.lat))
        var nearest=Double.POSITIVE_INFINITY
        var along=0.0
        var traveled=0.0
        for(i in 0 until points.lastIndex){
            val a=points[i];val b=points[i+1]
            val dx=(b.lon-a.lon)*lonScale
            val dy=(b.lat-a.lat)*latScale
            val px=(position.lon-a.lon)*lonScale
            val py=(position.lat-a.lat)*latScale
            val magnitude=dx*dx+dy*dy
            val t=if(magnitude<0.001)0.0
                else ((px*dx+py*dy)/magnitude).coerceIn(0.0,1.0)
            val lateral=hypot(px-dx*t,py-dy*t)
            val segment=RoutingEngine.meters(a,b)
            if(lateral<nearest){
                nearest=lateral
                along=traveled+segment*t
            }
            traveled+=segment
        }
        return Projection(along,nearest)
    }

    fun atDistance(points:List<RoutingEngine.Point>,meters:Double):
            RoutingEngine.Point {
        require(points.isNotEmpty())
        if(points.size==1)return points[0]
        var distance=meters.coerceAtLeast(0.0)
        for(i in 0 until points.lastIndex){
            val a=points[i];val b=points[i+1]
            val length=RoutingEngine.meters(a,b)
            if(distance<=length || i==points.lastIndex-1){
                val t=if(length<0.001)0.0
                    else (distance/length).coerceIn(0.0,1.0)
                return RoutingEngine.Point(a.lat+(b.lat-a.lat)*t,
                    a.lon+(b.lon-a.lon)*t)
            }
            distance-=length
        }
        return points.last()
    }

    /**
     * Bounded visual-only prediction along an accepted route. A stale GPS fix
     * must not visually turn the car at an intersection before its observed
     * heading actually points into the new street.
     */
    fun predictAhead(points:List<RoutingEngine.Point>,
                     position:RoutingEngine.Point,
                     measuredHeading:Double?,
                     meters:Double,
                     calibratedYawDegPerSec:Double?=null):RoutingEngine.Point? {
        if(points.size<2 || !meters.isFinite() || meters<0.0)return null
        val projected=project(points,position)?:return null
        if(projected.lateralMeters>35.0)return null
        var stopAt=projected.alongMeters+meters.coerceAtMost(22.0)
        var traveled=0.0
        for(i in 1 until points.lastIndex) {
            traveled+=RoutingEngine.meters(points[i-1],points[i])
            if(traveled < projected.alongMeters-1.0 ||
               traveled>stopAt+0.001)continue
            val incoming=bearing(points[i-1],points[i])
            val outgoing=bearing(points[i],points[i+1])
            val delta=((outgoing-incoming+540.0)%360.0)-180.0
            if(abs(delta)<35.0)continue
            val observed=measuredHeading?.takeIf{it.isFinite()}
            val headingToNew=observed?.let {
                abs(((outgoing-it+540.0)%360.0)-180.0)
            }
            val gyroTurn=calibratedYawDegPerSec?.takeIf{it.isFinite()}?.let{
                abs(it)>11.0 && sign(it)==sign(delta)
            }==true
            // A confirmed turn from real GPS course or a calibrated, fresh
            // gyro can release the corner guard; estimated course cannot.
            if((headingToNew==null || headingToNew>38.0) && !gyroTurn) {
                stopAt=min(stopAt,max(projected.alongMeters,traveled-1.0))
                break
            }
        }
        return atDistance(points,stopAt)
    }

    private fun bearing(a:RoutingEngine.Point,b:RoutingEngine.Point):Double =
        (Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),
            b.lat-a.lat))+360.0)%360.0

    fun remaining(points:List<RoutingEngine.Point>,meters:Double):
            List<RoutingEngine.Point> {
        if(points.size<2)return points
        var distance=meters.coerceAtLeast(0.0)
        for(i in 0 until points.lastIndex){
            val a=points[i];val b=points[i+1]
            val length=RoutingEngine.meters(a,b)
            if(distance<=length || i==points.lastIndex-1){
                val t=if(length<0.001)0.0
                    else (distance/length).coerceIn(0.0,1.0)
                val start=RoutingEngine.Point(a.lat+(b.lat-a.lat)*t,
                    a.lon+(b.lon-a.lon)*t)
                return listOf(start)+points.subList(i+1,points.size)
            }
            distance-=length
        }
        return listOf(points.last(),points.last())
    }
}
