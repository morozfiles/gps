package ua.obdgps.nav

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

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
        now:Long=SystemClock.elapsedRealtime()
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
        estimatedDriftMeters=(estimatedDriftMeters*(1.0-weight))
            .coerceAtLeast(location.accuracy.toDouble())
        correctionCount++
        lastNetworkStatus="Wi-Fi/сеть: поправка #$correctionCount · "+
            "от DR ${discrepancy.toInt()} м · точность ±${location.accuracy.toInt()} м"
        return true
    }
}
