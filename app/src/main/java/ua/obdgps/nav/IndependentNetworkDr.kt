package ua.obdgps.nav

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * GPS-cut simulation for the comparison screen.
 *
 * Initial geographic position and heading are supplied ONCE by the caller.
 * After start the only position inputs are:
 *   - calibrated gravity-axis gyro yaw,
 *   - vehicle speed from ELM327/OBD (usually via Bluetooth),
 *   - direct Android NETWORK_PROVIDER positions (Wi-Fi/cellular).
 *
 * Absolutely no LocationManager.GPS_PROVIDER, fused provider, map snapping,
 * magnetic/GPS course correction, or data from the main navigation DR.
 */
class IndependentNetworkDr {
    private val estimator=DeadReckoner()
    val pose:DeadReckoner.Pose? get()=if(running)estimator.pose else null
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
    private var previousNetwork:Location?=null
    private var previousNetworkReceivedMs=0L
    private var consistentNetworkSamples=0
    private var lastCorrectionMs=0L
    private var currentSpeedKmh:Double?=null

    fun start(lat:Double,lon:Double,bearing:Double,now:Long=SystemClock.elapsedRealtime()){
        require(lat.isFinite() && lat in -85.0..85.0 &&
            lon.isFinite() && lon in -180.0..180.0 && bearing.isFinite())
        estimator.seed(lat,lon,bearing)
        startedNanos=SystemClock.elapsedRealtimeNanos()
        lastTickMs=now
        previousNetwork=null
        previousNetworkReceivedMs=0L
        consistentNetworkSamples=0
        lastCorrectionMs=0L
        correctionCount=0
        currentSpeedKmh=null
        estimatedDriftMeters=0.0
        lastNetworkStatus="GPS отключён для DR · ожидаю Wi-Fi/сотовую сеть"
        running=true
    }

    fun stop() {
        running=false
        currentSpeedKmh=null
    }

    /** Rate from gyroscope only, without updating gyro/compass bias from GPS. */
    fun onGyroYaw(yawRadPerSecond:Double,dt:Double) {
        if(running)estimator.turn(yawRadPerSecond,dt)
    }

    /** Called by service clock. No GNSS fallback when OBD is missing. */
    fun advance(now:Long,obdKmh:Double?):DeadReckoner.Pose?{
        if(!running)return null
        val elapsed=((now-lastTickMs)/1000.0).coerceIn(0.0,0.45)
        lastTickMs=now
        currentSpeedKmh=obdKmh?.takeIf{it.isFinite() && it in 0.0..260.0}
        if(elapsed>0.0 && currentSpeedKmh!=null){
            estimator.move(currentSpeedKmh!!,elapsed)
            estimatedDriftMeters+=currentSpeedKmh!!/3.6*elapsed*0.045+0.12*elapsed
        }
        return estimator.pose
    }

    /**
     * Ignore everything except a fresh, direct, non-mock NETWORK_PROVIDER fix.
     * Fused is intentionally excluded because it may contain GNSS.
     * Cell tower results can be too coarse to safely correct the DR track.
     */
    fun onIndependentNetworkLocation(
        location:Location,sourceLabel:String,
        now:Long=SystemClock.elapsedRealtime()
    ):Boolean{
        if(!running)return false
        if(sourceLabel!="Wi-Fi/сеть" ||
            location.provider!=LocationManager.NETWORK_PROVIDER ||
            location.isMock)return false
        if(!location.hasAccuracy() || location.accuracy !in 1f..250f ||
            location.latitude !in -85.0..85.0 ||
            location.longitude !in -180.0..180.0 ||
            GpsSpoofGuard.isLima(location.latitude,location.longitude))return false
        val sensorNanos=location.elapsedRealtimeNanos
        val age=SystemClock.elapsedRealtimeNanos()-sensorNanos
        if(sensorNanos<startedNanos || age !in 0L..8_000_000_000L)return false
        val estimate=estimator.pose?:return false
        val anchor=Location(LocationManager.NETWORK_PROVIDER).apply{
            latitude=estimate.lat
            longitude=estimate.lon
        }
        val error=anchor.distanceTo(location).toDouble()
        val tolerance=max(80.0,min(270.0,
            location.accuracy.toDouble()*1.8+estimatedDriftMeters*1.25))
        if(error>tolerance){
            consistentNetworkSamples=0
            lastNetworkStatus="Сеть: расхождение ${error.toInt()} м · коррекция отклонена"
            return false
        }

        val previous=previousNetwork
        val seconds=(now-previousNetworkReceivedMs)/1000.0
        val consistent=previous!=null && seconds in 1.0..18.0 &&
            previous.distanceTo(location) <=
                max(85.0, (currentSpeedKmh?:0.0)/3.6*seconds+
                    previous.accuracy+location.accuracy+50.0)
        consistentNetworkSamples=if(consistent)
            (consistentNetworkSamples+1).coerceAtMost(5) else 1
        previousNetwork=Location(location)
        previousNetworkReceivedMs=now

        if(consistentNetworkSamples>=2 && now-lastCorrectionMs>=5000L){
            // Gentle correction: no snapping to uncertain network fixes.
            estimator.correctPosition(location.latitude,location.longitude,0.07)
            estimatedDriftMeters=(estimatedDriftMeters*0.94).coerceAtLeast(0.0)
            lastCorrectionMs=now
            correctionCount++
            lastNetworkStatus="Wi-Fi/сеть: поправка #$correctionCount · ~${location.accuracy.toInt()} м"
            return true
        }
        lastNetworkStatus="Wi-Fi/сеть: подтверждение ${consistentNetworkSamples}/2"
        return false
    }
}
