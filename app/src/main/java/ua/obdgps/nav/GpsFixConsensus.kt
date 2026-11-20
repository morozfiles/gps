package ua.obdgps.nav

import android.location.Location
import kotlin.math.*

/**
 * Conservative post-fix GNSS filter. Android LocationManager.GPS_PROVIDER
 * already supplies calculated positions (NOT per-satellite observations).
 *
 * A planned road route is only a plausibility reference, never used to
 * snap the accepted location onto roads. Valid recent GPS fixes are
 * motion-compensated and combined using a robust local weighted mean.
 */
class GpsFixConsensus {
    companion object {
        const val MAX_GPS_SPEED_KMH=180.0
        const val MAX_ROUTE_DEVIATION_METERS=500.0
        private const val EARTH_METERS_PER_DEGREE=111_195.0

        /** Minimum distance from a point to an entire route polyline. */
        fun routeDistanceMeters(
            latitude:Double,
            longitude:Double,
            points:List<RoutingEngine.Point>
        ):Double{
            if(points.size<2 || !latitude.isFinite() ||
                !longitude.isFinite())return Double.NaN
            val cosLat=cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
            val xScale=EARTH_METERS_PER_DEGREE*cosLat
            val yScale=EARTH_METERS_PER_DEGREE
            var prevX=shortestLonDiff(points[0].lon,longitude)*xScale
            var prevY=(points[0].lat-latitude)*yScale
            var closest=Double.POSITIVE_INFINITY
            for(i in 1 until points.size){
                val p=points[i]
                val x=shortestLonDiff(p.lon,longitude)*xScale
                val y=(p.lat-latitude)*yScale
                val vx=x-prevX
                val vy=y-prevY
                val denom=vx*vx+vy*vy
                val fraction=if(denom>1.0)
                    (-(prevX*vx+prevY*vy)/denom).coerceIn(0.0,1.0)
                else 0.0
                val d=hypot(prevX+fraction*vx,prevY+fraction*vy)
                if(d<closest)closest=d
                if(closest<=MAX_ROUTE_DEVIATION_METERS)return closest
                prevX=x;prevY=y
            }
            return closest
        }

        private fun shortestLonDiff(a:Double,b:Double):Double{
            val d=(a-b+540.0)%360.0-180.0
            return if(d < -180.0)d+360.0 else d
        }

        private fun median(values:List<Double>):Double{
            val v=values.sorted()
            return if(v.size%2==1)v[v.size/2]
            else (v[v.size/2-1]+v[v.size/2])*0.5
        }
    }

    private data class Sample(val location:Location,val receivedMs:Long)
    private val accepted=ArrayDeque<Sample>()
    val sampleCount:Int get()=accepted.size
    fun clear(){accepted.clear()}

    /**
     * Called ONLY after all speed, route, spoof and OBD/DR plausibility gates.
     * Returns a smoothed geographic position from recent accepted fixes.
     * Do not infer higher reported accuracy from averaging.
     */
    fun add(location:Location,now:Long):Location{
        if(accepted.lastOrNull()?.receivedMs != now)
            accepted.addLast(Sample(Location(location),now))
        while(accepted.isNotEmpty() &&
            now-accepted.first().receivedMs>5_000L)accepted.removeFirst()
        while(accepted.size>7)accepted.removeFirst()
        if(accepted.size<3)return Location(location)

        val ref=location
        val scaleX=EARTH_METERS_PER_DEGREE*
            cos(Math.toRadians(ref.latitude)).coerceAtLeast(0.01)
        val positions=accepted.map { sample ->
            val loc=sample.location
            var x=shortestLonDiff(loc.longitude,ref.longitude)*scaleX
            var y=(loc.latitude-ref.latitude)*EARTH_METERS_PER_DEGREE
            val dt=(now-sample.receivedMs).coerceAtLeast(0L)*0.001
            if(dt<=5.0 && loc.hasBearing() && loc.hasSpeed() &&
                loc.speed in 1f..50f &&
                (!loc.hasBearingAccuracy() ||
                    loc.bearingAccuracyDegrees<=25f)){
                val course=Math.toRadians(loc.bearing.toDouble())
                val travelled=loc.speed*dt
                x+=sin(course)*travelled
                y+=cos(course)*travelled
            }
            x to y
        }
        val medianX=median(positions.map{it.first})
        val medianY=median(positions.map{it.second})
        val radius=max(60.0,ref.accuracy.toDouble()*2.5)
        val inliers=positions.withIndex().filter{(_,xy)->
            hypot(xy.first-medianX,xy.second-medianY)<=radius
        }
        if(inliers.size<2)return Location(ref)
        var sumX=0.0
        var sumY=0.0
        var sumWeights=0.0
        for((index,xy) in inliers){
            // More recent observations receive more weight.
            val weight=(index+1).toDouble()
            sumX+=xy.first*weight
            sumY+=xy.second*weight
            sumWeights+=weight
        }
        val smoothX=sumX/sumWeights
        val smoothY=sumY/sumWeights
        // Never allow a historical cluster to pull a current position
        // more than 45 metres from this last individually validated fix.
        val displacement=hypot(smoothX,smoothY)
        val limit=45.0
        val alpha=if(displacement>limit)limit/displacement else 1.0
        return Location(ref).apply{
            latitude=ref.latitude+smoothY*alpha/EARTH_METERS_PER_DEGREE
            longitude=(ref.longitude+smoothX*alpha/scaleX+540.0)%360.0-180.0
        }
    }
}
