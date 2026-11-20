package ua.obdgps.nav

import android.os.SystemClock
import kotlin.math.*

/**
 * Display-only match of the independently calculated DR position to an
 * ALREADY downloaded/planned road route. The raw DR state, compass,
 * OBD values and GPS-spoof guard are never changed by this class.
 *
 * A projected route point is used only while the raw estimate remains
 * close to the route and its travel heading is plausible. We deliberately
 * stop snapping BEFORE we raise a sustained off-route/wrong-way alert,
 * so road guidance cannot hide a real departure or reverse travel.
 */
internal class InertialRouteMatcher {
    enum class Warning { NONE, OFF_ROUTE, WRONG_WAY }
    data class Result(
        val displayFix:State.NavFix,
        val rawDeviationMeters:Double,
        val snapped:Boolean,
        val warning:Warning,
        val warningText:String
    )

    private data class Match(
        val point:RoutingEngine.Point,
        val distance:Double,
        val bearing:Double,
        val along:Double
    )

    private var routeIdentity:RoutingEngine.Route?=null
    private var prefix=DoubleArray(0)
    private var offSince=0L
    private var wrongSince=0L
    private var wrongTravelMeters=0.0
    private var lastTick=0L
    private var lastRouteDistance=Double.NaN
    private var warning=Warning.NONE
    private var lastMatchedAlong=Double.NaN
    private var lastMatchedAt=0L

    fun reset(){
        routeIdentity=null
        prefix=DoubleArray(0)
        offSince=0L;wrongSince=0L;wrongTravelMeters=0.0
        lastTick=0L;lastRouteDistance=Double.NaN
        lastMatchedAlong=Double.NaN;lastMatchedAt=0L
        warning=Warning.NONE
    }

    fun match(
        fix:State.NavFix,route:RoutingEngine.Route,
        speedKmh:Double?,now:Long=SystemClock.elapsedRealtime()
    ):Result{
        if(routeIdentity!==route){
            reset()
            routeIdentity=route
            prefix=DoubleArray(route.points.size)
            for(i in 1 until route.points.size) {
                prefix[i]=prefix[i-1]+RoutingEngine.meters(
                    route.points[i-1],route.points[i])
            }
        }
        // Use ONLY the DR position for matching. Ordinary GPS navigation
        // remains unchanged and cannot accidentally enter this matcher.
        if(!State.dr || !fix.source.contains("инерциальные") ||
            route.points.size<2){
            reset()
            return Result(fix,0.0,false,Warning.NONE,"")
        }
        val raw=RoutingEngine.Point(fix.lat,fix.lon)
        val estimated=findNearest(raw,route.points) ?:return Result(
            fix,Double.POSITIVE_INFINITY,false,Warning.NONE,"")
        val kmh=speedKmh?.takeIf{it.isFinite() && it in 0.0..260.0}
        val dt=if(lastTick>0L)((now-lastTick)/1000.0).coerceIn(0.0,2.0)
            else 0.0
        lastTick=now

        val headingDifference=fix.bearing?.takeIf{it.isFinite()}?.let{
            abs(((it-estimated.bearing+540.0)%360.0)-180.0)
        }
        val moving=(kmh?:0.0)>=7.0
        val facingBack=moving && headingDifference!=null &&
            headingDifference>=125.0 && estimated.distance<85.0
        if(facingBack) {
            if(wrongSince==0L)wrongSince=now
            wrongTravelMeters+=(kmh?:0.0)/3.6*dt
        }else {
            wrongSince=0L
            wrongTravelMeters=0.0
        }
        // A large cross-track distance can be due to DR drift. A warning
        // requires several seconds of persistent discrepancy.
        val severe=estimated.distance>180.0
        val displaced=estimated.distance>75.0 && (kmh?:0.0)>=4.0
        if(displaced) {
            if(offSince==0L)offSince=now
        }else offSince=0L

        warning=when{
            wrongSince>0L && now-wrongSince>=9_000L &&
                wrongTravelMeters>=22.0 -> Warning.WRONG_WAY
            offSince>0L && now-offSince>=
                (if(severe)4_000L else 9_000L)->Warning.OFF_ROUTE
            else->Warning.NONE
        }
        val warningText=when(warning){
            Warning.WRONG_WAY->"Внимание: движение против направления маршрута"
            Warning.OFF_ROUTE->"Внимание: сход с маршрута, отклонение "+
                estimated.distance.roundToInt()+" м"
            Warning.NONE->""
        }

        // Never snap a distant or clearly opposing raw inertial position.
        // Constrain progression on hairpins and self-crossing road routes:
        // a short DR tick must not teleport hundreds of meters along a
        // different section just because the geometry crosses there.
        val backward=facingBack || warning==Warning.WRONG_WAY
        val maxTravel=(kmh?:0.0)/3.6*
            ((now-lastMatchedAt).coerceIn(0L,5_000L)/1000.0)+45.0
        val jump=lastMatchedAlong.isFinite() && lastMatchedAt>0L &&
            now-lastMatchedAt<6_000L &&
            (estimated.along-lastMatchedAlong>maxTravel ||
                lastMatchedAlong-estimated.along>55.0)
        val maySnap=warning==Warning.NONE &&
            !backward && !displaced && !jump &&
            estimated.distance<=45.0 &&
            (headingDifference==null || !moving || headingDifference<=75.0)
        val displayed=if(maySnap) {
            lastMatchedAlong=estimated.along
            lastMatchedAt=now
            fix.copy(lat=estimated.point.lat,lon=estimated.point.lon,
                // Heading is still measured by inertial sensors, NOT
                // replaced by an expected tangent to the route.
                source="OBD + инерциальные датчики · маршрут")
        }else fix
        lastRouteDistance=estimated.distance
        return Result(displayed,estimated.distance,maySnap,
            warning,warningText)
    }

    private fun findNearest(
        raw:RoutingEngine.Point,points:List<RoutingEngine.Point>
    ):Match?{
        if(points.size<2)return null
        val scaleLat=111_195.0
        val cosLat=cos(Math.toRadians(raw.lat)).coerceAtLeast(0.01)
        val scaleLon=111_195.0*cosLat
        var nearest:Match?=null
        // Scan contiguous original route segments, never external OSM
        // roads. Using a foreign road here could misroute the vehicle.
        for(i in 0 until points.lastIndex){
            val a=points[i];val b=points[i+1]
            val ax=(a.lon-raw.lon)*scaleLon
            val ay=(a.lat-raw.lat)*scaleLat
            val bx=(b.lon-raw.lon)*scaleLon
            val by=(b.lat-raw.lat)*scaleLat
            val vx=bx-ax;val vy=by-ay
            val length2=vx*vx+vy*vy
            if(length2<0.25)continue
            val t=(-(ax*vx+ay*vy)/length2).coerceIn(0.0,1.0)
            val dx=ax+t*vx;val dy=ay+t*vy
            val dist=hypot(dx,dy)
            if(nearest==null || dist<nearest.distance) {
                val bearing=(Math.toDegrees(atan2(vx,vy))+360.0)%360.0
                nearest=Match(
                    RoutingEngine.Point(a.lat+(b.lat-a.lat)*t,
                        a.lon+(b.lon-a.lon)*t),
                    dist,bearing,prefix[i]+sqrt(length2)*t)
            }
        }
        return nearest
    }
}
