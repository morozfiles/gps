package ua.obdgps.nav

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import kotlin.math.max

/**
 * FYT diagnostic GPS-cut simulation. Blue track receives ONLY:
 * - OBD speed via Bluetooth Vgate/ELM327,
 * - physical gyro yaw,
 * - Android's direct, non-mock NETWORK_PROVIDER (Wi-Fi/cellular positioning).
 *
 * The independent *raw* estimator has NO location correction at all. It is
 * used for learning GPS-vs-OBD/gyro gains, even if the visible blue route is
 * re-anchored by an accurate network fix several kilometres away.
 *
 * The strong network re-anchor is for this explicit diagnostic test ONLY.
 * It does not change the main navigator's spoofing or recovery rules.
 * ELM327/Bluetooth itself provides vehicle speed, not geographical position.
 */
class IndependentNetworkDr {
    private val estimator=DeadReckoner()
    private val trainingEstimator=DeadReckoner()
    val pose:DeadReckoner.Pose? get()=if(running)estimator.pose else null
    val trainingPose:DeadReckoner.Pose? get()=
        if(running)trainingEstimator.pose else null
    var running=false
        private set
    var correctionCount=0
        private set
    var lastNetworkStatus="Сетевых коррекций нет"
        private set
    var estimatedDriftMeters=0.0
        private set
    var networkLeftTurns=0
        private set
    var networkRightTurns=0
        private set
    private val networkTurnCounter=TrustedTurnCounter()
    private var lastCourseAnchor:Location?=null
    private var lastCourseAnchorMs=0L

    private var startedNanos=0L
    private var lastTickMs=0L
    private var currentSpeedKmh:Double?=null

    fun start(lat:Double,lon:Double,bearing:Double,now:Long=SystemClock.elapsedRealtime()){
        require(lat.isFinite() && lat in -85.0..85.0 &&
            lon.isFinite() && lon in -180.0..180.0 && bearing.isFinite())
        estimator.seed(lat,lon,bearing)
        trainingEstimator.seed(lat,lon,bearing)
        startedNanos=SystemClock.elapsedRealtimeNanos()
        lastTickMs=now
        correctionCount=0
        currentSpeedKmh=null
        estimatedDriftMeters=0.0
        networkLeftTurns=0
        networkRightTurns=0
        networkTurnCounter.reset()
        lastCourseAnchor=null
        lastCourseAnchorMs=0L
        lastNetworkStatus="GPS исключён из DR · жду Wi-Fi/сеть"
        running=true
    }

    fun stop() {
        running=false
        currentSpeedKmh=null
    }

    fun onGyroYaw(yawRadPerSecond:Double,dt:Double) {
        if(!running)return
        estimator.turn(yawRadPerSecond,dt)
        trainingEstimator.turn(yawRadPerSecond,dt)
    }

    /** The two estimators move identically until a network correction. */
    fun advance(now:Long,obdKmh:Double?):DeadReckoner.Pose?{
        if(!running)return null
        val elapsed=((now-lastTickMs)/1000.0).coerceIn(0.0,0.45)
        lastTickMs=now
        currentSpeedKmh=obdKmh?.takeIf{it.isFinite() && it in 0.0..260.0}
        if(elapsed>0.0 && currentSpeedKmh!=null) {
            estimator.move(currentSpeedKmh!!,elapsed)
            trainingEstimator.move(currentSpeedKmh!!,elapsed)
            estimatedDriftMeters+=currentSpeedKmh!!/3.6*elapsed*0.045+0.12*elapsed
        }
        return estimator.pose
    }

    /**
     * Accept EVERY fresh independent NETWORK_PROVIDER fix even when it is
     * 1+ km from calculated DR. The provider accuracy (not discrepancy
     * from DR) determines how far to move the blue trace toward the fix.
     * No 270m proximity gate, consecutive-fix prerequisite or 5s delay.
     *
     * Do not use an injected mock/fused/GPS fix as a network reference.
     */
    fun onIndependentNetworkLocation(
        location:Location,sourceLabel:String,
        now:Long=SystemClock.elapsedRealtime(),
        gyroFresh:Boolean=true
    ):Boolean{
        if(!running)return false
        if(sourceLabel!="Wi-Fi/сеть" ||
            location.provider!=LocationManager.NETWORK_PROVIDER ||
            location.isMock)return false
        if(!location.hasAccuracy() || location.accuracy !in 1f..1000f ||
            location.latitude !in -85.0..85.0 ||
            location.longitude !in -180.0..180.0 ||
            GpsSpoofGuard.isLima(location.latitude,location.longitude))return false
        val sensorNanos=location.elapsedRealtimeNanos
        val age=SystemClock.elapsedRealtimeNanos()-sensorNanos
        if(sensorNanos<startedNanos || age !in 0L..8_000_000_000L)return false
        val current=estimator.pose?:return false
        val anchor=Location(LocationManager.NETWORK_PROVIDER).apply{
            latitude=current.lat
            longitude=current.lon
        }
        val discrepancy=anchor.distanceTo(location).toDouble()
        val weight=NetworkCorrectionPolicy.weight(location.accuracy.toDouble())
        estimator.correctPosition(location.latitude,location.longitude,weight)
        // Recover heading from two accurate, speed-consistent network
        // positions when an Android FYT ROM exposes no working gyro.
        // The network-derived course never enters trainingEstimator.
        val courseAnchor=lastCourseAnchor
        val seconds=(now-lastCourseAnchorMs)/1000.0
        if(location.accuracy<=65f && currentSpeedKmh!=null) {
            if(courseAnchor==null) {
                lastCourseAnchor=Location(location)
                lastCourseAnchorMs=now
            }else if(seconds in 1.0..40.0){
                val travel=courseAnchor.distanceTo(location).toDouble()
                val needed=max(24.0,
                    (courseAnchor.accuracy+location.accuracy)*1.6)
                val maximum=max(95.0,
                    currentSpeedKmh!!/3.6*seconds*1.8+
                        courseAnchor.accuracy+location.accuracy+65.0)
                if(travel>=needed && travel<=maximum) {
                    val course=courseAnchor.bearingTo(location).toDouble()
                    estimator.correctHeadingFromNetworkForTest(
                        course,if(gyroFresh)0.15 else 0.85)
                    when(networkTurnCounter.observe(course,now)) {
                        TrustedTurnCounter.Direction.LEFT->networkLeftTurns++
                        TrustedTurnCounter.Direction.RIGHT->networkRightTurns++
                        null->{}
                    }
                    lastCourseAnchor=Location(location)
                    lastCourseAnchorMs=now
                }
            }else if(seconds>40.0) {
                lastCourseAnchor=Location(location)
                lastCourseAnchorMs=now
                networkTurnCounter.reset()
            }
        }
        estimatedDriftMeters=(estimatedDriftMeters*(1.0-weight))
            .coerceAtLeast(location.accuracy.toDouble())
        correctionCount++
        lastNetworkStatus="Wi-Fi/сеть: +$correctionCount поправок · "+
            "от DR ${discrepancy.toInt()} м · ±${location.accuracy.toInt()} м"+
            " · повороты сети ←$networkLeftTurns →$networkRightTurns"
        return true
    }
}
