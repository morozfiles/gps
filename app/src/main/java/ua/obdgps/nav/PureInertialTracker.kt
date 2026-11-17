package ua.obdgps.nav

import kotlin.math.*

/**
 * Experimental sensor-only dead reckoning. The single initial coordinate
 * and heading are supplied explicitly at start. From then on this estimator
 * receives ONLY linear acceleration and gravity-axis gyro yaw; no GPS, OBD,
 * network, cell tower, Wi-Fi, Bluetooth, compass or map matching is read.
 *
 * The phone must be rigidly mounted, initially stationary for bias sampling.
 * Integration without external corrections inevitably drifts.
 */
class PureInertialTracker {
    data class Pose(
        val lat:Double,val lon:Double,val bearing:Double,
        val speedMetersPerSecond:Double
    )
    private var position:Pose?=null
    private var lastAccelNanos=0L
    private var baselineStartNanos=0L
    private var baselineCount=0
    private var baselineSum=0.0
    private var accelerationBias=0.0
    var calibrating=true
        private set
    val pose:Pose? get()=position

    fun start(lat:Double,lon:Double,bearing:Double){
        require(lat.isFinite() && lat in -85.0..85.0 &&
            lon.isFinite() && lon in -180.0..180.0 &&
            bearing.isFinite())
        position=Pose(lat,lon,wrap(bearing),0.0)
        calibrating=true
        baselineStartNanos=0L
        lastAccelNanos=0L
        baselineCount=0
        baselineSum=0.0
        accelerationBias=0.0
    }

    fun stop(){
        position=null
        lastAccelNanos=0L
        calibrating=false
    }

    /** Same yaw sign as the existing gravity-axis gyro DeadReckoner. */
    fun turn(yawRadPerSecond:Double, dt:Double){
        if(dt !in 0.001..0.15 || !yawRadPerSecond.isFinite())return
        val p=position?:return
        position=p.copy(bearing=wrap(p.bearing-Math.toDegrees(yawRadPerSecond*dt)))
    }

    fun acceleration(forward:Double,timeNanos:Long){
        if(!forward.isFinite()||timeNanos<=0L)return
        val p=position?:return
        if(baselineStartNanos==0L)baselineStartNanos=timeNanos
        if(calibrating){
            if(abs(forward)>8.0)return
            baselineSum+=forward
            baselineCount++
            lastAccelNanos=timeNanos
            if(timeNanos-baselineStartNanos>=2_000_000_000L &&
                baselineCount>=30) {
                accelerationBias=baselineSum/baselineCount
                calibrating=false
            }
            return
        }
        val dt=(timeNanos-lastAccelNanos)*1e-9
        lastAccelNanos=timeNanos
        if(dt<=0.0 || dt>0.2)return
        val acceleration=(forward-accelerationBias).coerceIn(-15.0,15.0)
        // No external speed correction and no forced stopping. Any sensor
        // bias integrates into an increasing velocity/position error.
        val nextSpeed=(p.speedMetersPerSecond+acceleration*dt)
            .coerceIn(-75.0,75.0)
        val distance=(p.speedMetersPerSecond+nextSpeed)*0.5*dt
        val angular=distance/6_371_000.0
        val phi=Math.toRadians(p.lat)
        val lambda=Math.toRadians(p.lon)
        val course=Math.toRadians(p.bearing)
        val nextLat=asin((sin(phi)*cos(angular)+
            cos(phi)*sin(angular)*cos(course)).coerceIn(-1.0,1.0))
        val nextLon=lambda+atan2(sin(course)*sin(angular)*cos(phi),
            cos(angular)-sin(phi)*sin(nextLat))
        position=p.copy(lat=Math.toDegrees(nextLat),
            lon=(Math.toDegrees(nextLon)+540.0)%360.0-180.0,
            speedMetersPerSecond=nextSpeed)
    }

    private fun wrap(x:Double)=((x%360.0)+360.0)%360.0
}

/**
 * A geographic clue is not a claim about the cause of interference.
 * An apparent Lima fix is incompatible with a test trip in Ukraine.
 */
object GpsSpoofGuard {
    fun isLima(latitude:Double,longitude:Double):Boolean =
        latitude in -15.0..-9.0 && longitude in -80.0..-74.0

    /** Independently verified position is required before unquarantining. */
    fun plausibleRecovery(distanceMeters:Double,reportedSpeedKmh:Double?):Boolean =
        distanceMeters.isFinite() && distanceMeters<=250.0 &&
            reportedSpeedKmh!=null &&
            reportedSpeedKmh.isFinite() &&
            reportedSpeedKmh in 0.0..180.0
}
