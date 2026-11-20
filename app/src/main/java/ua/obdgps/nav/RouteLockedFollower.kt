package ua.obdgps.nav

import android.os.SystemClock
import kotlin.math.*

/**
 * Route-constrained fallback. This is an ESTIMATE, not a measurement of position.
 * The already planned route is immutable; only the progress cursor is adjusted.
 * OBD speed owns distance when fresh. Gyro yaw helps identify the next turn.
 * Every outside fix is gated and nudges along the route, never teleports the car.
 */
class RouteLockedFollower {
    private var route:RoutingEngine.Route?=null
    private var cumulative=DoubleArray(0)
    private var distance=0.0
    private var updatedAt=0L
    private var lastExternalAt=0L
    private var yawAccum=0.0
    private var lastTurnAt=0L
    private var uncertainty=35.0
    private var initialized=false
    var status:String="Маршрутная оценка"
        private set

    fun reset() {
        route=null; cumulative=DoubleArray(0); distance=0.0
        updatedAt=0L; lastExternalAt=0L; yawAccum=0.0
        lastTurnAt=0L; uncertainty=35.0; initialized=false
    }

    fun bind(next:RoutingEngine.Route, anchor:State.NavFix?) {
        if(route===next)return
        reset()
        route=next
        val points=next.points
        cumulative=DoubleArray(points.size)
        for(i in 1 until points.size)
            cumulative[i]=cumulative[i-1]+RoutingEngine.meters(points[i-1],points[i])
        val found=anchor?.let{ project(RoutingEngine.Point(it.lat,it.lon)) }
        if(found!=null && found.second<300.0) distance=found.first
        updatedAt=SystemClock.elapsedRealtime()
        initialized=true
    }

    /** MotionSensors reports a signed gravity-axis yaw rate in radians/s. */
    fun onYaw(rate:Double,dt:Double) {
        if(!rate.isFinite() || !dt.isFinite() || dt<=0.0 || dt>0.4)return
        yawAccum=(yawAccum+-Math.toDegrees(rate*dt)).coerceIn(-150.0,150.0)
    }

    fun update(
        now:Long,
        obdKmh:Double?,
        obdFresh:Boolean,
        observed:State.NavFix?,
        network:State.NavFix?,
        alertActive:Boolean
    ):State.NavFix? {
        val r=route?:return null
        if(!initialized || r.points.size<2)return null
        val dt=if(updatedAt==0L)0.0 else ((now-updatedAt)/1000.0).coerceIn(0.0,3.0)
        updatedAt=now
        val speed=obdKmh?.takeIf{obdFresh && it.isFinite() && it in 0.0..180.0}
        if(speed!=null) {
            distance=(distance+speed/3.6*dt).coerceAtMost(cumulative.last())
            uncertainty=(uncertainty+(0.35+speed/150.0)*dt).coerceAtMost(1000.0)
            status="OBD + маршрут"
        }else status="OBD недоступен — движение по маршруту приостановлено"

        // Gyroscope only helps identify a nearby *planned* intersection.
        // It cannot prove that the car took a particular street or lane.
        if(speed!=null && speed>5.0 && now-lastTurnAt>3500L &&
            abs(yawAccum) in 28.0..135.0) {
            val turn=r.maneuvers.firstOrNull {
                val index=it.shapeIndex.coerceIn(1,cumulative.lastIndex-1)
                val at=cumulative[index]
                val before=bearing(r.points[index-1],r.points[index])
                val after=bearing(r.points[index],r.points[index+1])
                val delta=angle(after-before)
                at>=distance-22.0 && at<=distance+max(35.0,speed/3.6*3.0) &&
                    abs(delta)>23.0 && sign(delta)==sign(yawAccum)
            }
            if(turn!=null) {
                val at=cumulative[turn.shapeIndex.coerceIn(0,cumulative.lastIndex)]
                // Small bounded alignment, no jump to a remote junction.
                distance=max(distance,min(at+4.0,distance+18.0))
                yawAccum=0.0
                lastTurnAt=now
                status="OBD + подтверждённый гироскопом поворот"
            }
        }
        // Decay old integrated yaw so vibration does not trigger late turns.
        yawAccum*=if(speed==null || speed<3.0)0.82 else 0.975

        // Phone GNSS can be spoofed. Its provider label and accuracy alone
        // are not proof of authenticity, especially during air alerts.
        observed?.takeIf {
            it.source=="GPS" && it.accuracy>0f && it.accuracy<=250f &&
                now-it.elapsedMs in 0L..5500L
        }?.let{correct(it,now,alertActive,true,speed)}

        // Android NETWORK_PROVIDER is already fused from Wi-Fi/cell;
        // a Bluetooth device is evidence only if it provides a real location fix.
        network?.takeIf{
            now-it.elapsedMs in 0L..15000L && it.accuracy>0f &&
                it.accuracy<=2000f
        }?.let{correct(it,now,alertActive,false,speed)}

        val point=at(distance)
        val heading=courseAt(distance)
        return State.NavFix(point.lat,point.lon,heading,
            uncertainty.coerceIn(15.0,1000.0).toFloat(),
            "OBD · по маршруту (оценка)",now)
    }

    private fun correct(fix:State.NavFix,now:Long,alert:Boolean,
                        satellite:Boolean,speed:Double?) {
        // Same fix must not be counted repeatedly at each UI refresh.
        if(fix.elapsedMs<=lastExternalAt)return
        if(fix.lat !in -85.0..85.0 || fix.lon !in -180.0..180.0)return
        val projected=project(RoutingEngine.Point(fix.lat,fix.lon))?:return
        val (position,lateral)=projected
        val accuracy=fix.accuracy.toDouble()
        if(lateral>max(120.0,min(300.0,accuracy*2.0)))return
        val discrepancy=position-distance
        val window=max(80.0,min(450.0,accuracy*2.5+uncertainty))
        // Reject teleport / implausible route progress, even if accuracy says 5m.
        if(abs(discrepancy)>window)return
        // With OBD, do not let a repeated external fix run the estimate backward.
        val gain=if(satellite) {
            if(alert)0.045 else if(accuracy<=25.0)0.20 else 0.08
        } else if(alert)0.025 else 0.055
        val step=(discrepancy*gain).coerceIn(-(if(speed==null)3.0 else 1.5), 
            if(satellite)8.0 else 4.0)
        distance=(distance+step).coerceIn(0.0,cumulative.last())
        uncertainty=(uncertainty*0.98).coerceAtLeast(20.0)
        lastExternalAt=fix.elapsedMs
        status+=if(satellite)" · мягкая GNSS-коррекция" else " · сетевая коррекция"
    }

    private fun project(p:RoutingEngine.Point):Pair<Double,Double>? {
        val r=route?:return null
        if(r.points.size<2)return null
        var bestAlong=0.0
        var bestLateral=Double.POSITIVE_INFINITY
        // Search locally while following, prevents switching to a parallel
        // stretch of the same route or jumping across a loop.
        for(i in 0 until r.points.lastIndex) {
            if(initialized && (cumulative[i+1]<distance-500.0 ||
                    cumulative[i]>distance+500.0))continue
            val a=r.points[i];val b=r.points[i+1]
            val latitude=Math.toRadians((p.lat+a.lat+b.lat)/3.0)
            val scale=6371000.0
            val ax=Math.toRadians(a.lon-p.lon)*cos(latitude)*scale
            val ay=Math.toRadians(a.lat-p.lat)*scale
            val bx=Math.toRadians(b.lon-p.lon)*cos(latitude)*scale
            val by=Math.toRadians(b.lat-p.lat)*scale
            val vx=bx-ax;val vy=by-ay
            val length=vx*vx+vy*vy
            val t=if(length<0.001)0.0 else
                (-(ax*vx+ay*vy)/length).coerceIn(0.0,1.0)
            val lateral=hypot(ax+t*vx,ay+t*vy)
            if(lateral<bestLateral) {
                bestLateral=lateral
                bestAlong=cumulative[i]+(cumulative[i+1]-cumulative[i])*t
            }
        }
        return if(bestLateral.isFinite())bestAlong to bestLateral else null
    }

    private fun at(meters:Double):RoutingEngine.Point {
        val p=route!!.points
        val d=meters.coerceIn(0.0,cumulative.last())
        val idx=cumulative.binarySearch(d)
        if(idx>=0)return p[idx]
        val i=(-idx-2).coerceIn(0,p.lastIndex-1)
        val length=cumulative[i+1]-cumulative[i]
        val t=if(length<0.01)0.0 else (d-cumulative[i])/length
        return RoutingEngine.Point(p[i].lat+(p[i+1].lat-p[i].lat)*t,
            p[i].lon+(p[i+1].lon-p[i].lon)*t)
    }

    private fun courseAt(meters:Double):Double {
        val p=route!!.points
        val ix=cumulative.binarySearch(meters.coerceIn(0.0,cumulative.last()))
        val i=if(ix>=0)ix.coerceAtMost(p.lastIndex-1)
            else (-ix-2).coerceIn(0,p.lastIndex-1)
        return bearing(p[i],p[i+1])
    }

    private fun bearing(a:RoutingEngine.Point,b:RoutingEngine.Point):Double {
        val y=(b.lon-a.lon)*cos(Math.toRadians((a.lat+b.lat)/2))
        val x=b.lat-a.lat
        return (Math.toDegrees(atan2(y,x))+360.0)%360.0
    }

    private fun angle(deg:Double)=((deg+540.0)%360.0)-180.0
}
