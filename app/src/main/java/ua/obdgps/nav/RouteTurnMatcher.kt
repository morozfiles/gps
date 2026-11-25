package ua.obdgps.nav

import kotlin.math.*

/**
 * Detect turns from the route SHAPE, not maneuver indices supplied by Google.
 * Works when an offline/stored route has no maneuver metadata at all.
 */
internal object RouteTurnMatcher {
    data class Bend(val vertex:Int,val meters:Double,val signedAngleDeg:Double)

    fun cumulative(points:List<RoutingEngine.Point>):DoubleArray {
        val distances=DoubleArray(points.size)
        for(i in 1 until points.size)
            distances[i]=distances[i-1]+RoutingEngine.meters(points[i-1],points[i])
        return distances
    }

    private fun bearing(a:RoutingEngine.Point,b:RoutingEngine.Point):Double =
        (Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),
            b.lat-a.lat))+360.0)%360.0

    private fun pointAt(points:List<RoutingEngine.Point>,distances:DoubleArray,
                        meters:Double):RoutingEngine.Point {
        if(points.size==1)return points[0]
        val at=meters.coerceIn(0.0,distances.last())
        var low=0;var high=distances.lastIndex
        while(low+1<high){
            val middle=(low+high)/2
            if(distances[middle]<=at)low=middle else high=middle
        }
        val segment=(distances[low+1]-distances[low]).coerceAtLeast(0.001)
        val t=((at-distances[low])/segment).coerceIn(0.0,1.0)
        return RoutingEngine.Point(
            points[low].lat+(points[low+1].lat-points[low].lat)*t,
            points[low].lon+(points[low+1].lon-points[low].lon)*t)
    }

    /** O(log N) route sampling for per-frame marker/camera animation. */
    fun positionAt(points:List<RoutingEngine.Point>,
                   cumulative:DoubleArray,meters:Double):RoutingEngine.Point =
        pointAt(points,cumulative,meters)

    fun findBends(points:List<RoutingEngine.Point>,
                  distances:DoubleArray=cumulative(points)):List<Bend> {
        if(points.size<3 || distances.size!=points.size)return emptyList()
        val result=ArrayList<Bend>()
        for(i in 1 until points.lastIndex){
            val before=distances[i]-18.0
            val after=distances[i]+18.0
            if(before<0.0 || after>distances.last())continue
            val a=pointAt(points,distances,before)
            val b=pointAt(points,distances,after)
            val here=points[i]
            if(RoutingEngine.meters(a,here)<4.0 ||
               RoutingEngine.meters(here,b)<4.0)continue
            val incoming=bearing(a,here)
            val outgoing=bearing(here,b)
            val angle=((outgoing-incoming+540.0)%360.0)-180.0
            if(abs(angle)<33.0)continue
            val candidate=Bend(i,distances[i],angle)
            if(result.isNotEmpty() && candidate.meters-result.last().meters<25.0){
                if(abs(candidate.signedAngleDeg)>abs(result.last().signedAngleDeg))
                    result[result.lastIndex]=candidate
            }else result.add(candidate)
        }
        return result
    }

    /** Nearest confirmed turn may be BEHIND or AHEAD of an OBD odometer.
     * A symmetrical window corrects both early and late counted turns.
     * Reusing the same vertex is forbidden to avoid oscillation.
     */
    fun choose(bends:List<Bend>,positionMeters:Double,
               turnedDegrees:Double,excludedVertex:Int=-1):Bend? {
        if(!turnedDegrees.isFinite() || abs(turnedDegrees)<12.0)return null
        val window=125.0
        return bends.asSequence().filter {
            it.vertex!=excludedVertex &&
                sign(it.signedAngleDeg)==sign(turnedDegrees) &&
                abs(it.meters-positionMeters)<=window
        }.minByOrNull { abs(it.meters-positionMeters) }
    }

    fun correctionStep(remainingMeters:Double,elapsedSeconds:Double):Double {
        if(!remainingMeters.isFinite() || abs(remainingMeters)<0.3)return 0.0
        val fraction=(elapsedSeconds*1.5).coerceIn(0.10,0.55)
        val maxMeters=(elapsedSeconds*12.0).coerceIn(0.6,5.0)
        return (remainingMeters*fraction).coerceIn(-maxMeters,maxMeters)
    }
}
