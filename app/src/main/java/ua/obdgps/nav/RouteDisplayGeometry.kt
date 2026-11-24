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
