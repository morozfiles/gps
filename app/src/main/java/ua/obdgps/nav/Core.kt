package ua.obdgps.nav

import kotlin.math.*

object Obd {
    private val pattern = Regex("410D([0-9A-F]{2})")
    fun parseSpeed(s: String): Int? {
        val cleaned = s.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        return pattern.find(cleaned)?.groupValues?.get(1)?.toIntOrNull(16)
    }
}
/**
 * Mitsubishi MUT-II request 0x2F: vehicle speed = unsigned byte * 2 km/h.
 * A native MUT reply (not an ELM echo or standard OBD response) is required.
 * Vgate/ELM UART may not expose native raw MUT packets; fail closed.
 */
object MitsubishiMut {
    fun parseSpeed(raw: String): Int? {
        val text = raw.uppercase().replace("\\r", " ").replace("\\n", " ")
        if (listOf("NO DATA","ERROR","TIMEOUT","UNABLE","BUS INIT","STOPPED").any { it in text }) return null
        // Only accept a reply that explicitly contains the native MUT item 2F.
        val response = Regex("""(?<![0-9A-F])2F[ :]+([0-9A-F]{2})(?![0-9A-F])""").find(text)
            ?: Regex("""(?<![0-9A-F])2F([0-9A-F]{2})(?![0-9A-F])""").find(text)
            ?: return null
        return response.groupValues[1].toInt(16) * 2
    }
}

class DeadReckoner {
    data class Pose(val lat: Double, val lon: Double, val bearing: Double)
    var pose: Pose? = null
        private set
    fun seed(lat: Double, lon: Double, bearing: Double) {
        pose = Pose(lat, lon, (bearing + 360.0) % 360.0)
    }
    fun turn(rateRad: Double, dt: Double) {
        if (dt <= 0.0 || dt > 0.5) return
        pose = pose?.let { it.copy(bearing = (it.bearing - Math.toDegrees(rateRad * dt) + 720) % 360) }
    }
    fun correctPosition(lat: Double, lon: Double, blend: Double) {
        val p=pose ?: return
        val alpha=blend.coerceIn(0.0,0.25)
        // Only for short distances, after plausibility checks in NavService.
        pose=p.copy(lat=p.lat+(lat-p.lat)*alpha,lon=p.lon+(lon-p.lon)*alpha)
    }
    fun correctHeading(target: Double, maxChangeDegrees: Double) {
        val p=pose ?: return
        val difference=(((target-p.bearing+540)%360+360)%360)-180
        if(abs(difference)>20) return
        pose=p.copy(bearing=(p.bearing + difference.coerceIn(-maxChangeDegrees,maxChangeDegrees)+360)%360)
    }
    fun move(kmh: Double, dt: Double, reverse: Boolean = false) {
        if (dt <= 0 || dt > 1 || !kmh.isFinite() || kmh !in 0.0..260.0) return
        val p = pose ?: return
        val dist = (kmh / 3.6) * dt / 6371000
        val a = Math.toRadians(p.lat)
        val b = Math.toRadians(p.lon)
        val heading = Math.toRadians((p.bearing + if(reverse) 180.0 else 0.0)%360.0)
        val nextLat = asin((sin(a) * cos(dist) + cos(a) * sin(dist) * cos(heading)).coerceIn(-1.0,1.0))
        val nextLon = b + atan2(sin(heading) * sin(dist) * cos(a), cos(dist)-sin(a)*sin(nextLat))
        pose = p.copy(lat = Math.toDegrees(nextLat), lon = (Math.toDegrees(nextLon) + 540) % 360 - 180)
    }
}
object State {
    /** Only values calculated by DeadReckoner are written here.
     * No LocationManager/GPS or Google Fused locations are ever plotted.
     * In-memory trail is intentionally cleared at the start of a new DR run.
     */
    data class TrailPoint(
        val latitude: Double,
        val longitude: Double,
        val bearing: Double,
        val speedKmh: Double,
        val elapsedMs: Long
    )
    val inertialTrail = ArrayList<TrailPoint>()
    var inertialPoint: TrailPoint? = null
        private set

    fun clearInertialTrail() {
        inertialTrail.clear()
        inertialPoint = null
    }

    fun recordInertialPoint(
        lat: Double, lon: Double, bearing: Double,
        speedKmh: Double, elapsedMs: Long
    ) {
        if (!lat.isFinite() || !lon.isFinite() || !bearing.isFinite() ||
            !speedKmh.isFinite() || lat !in -85.0..85.0 ||
            lon !in -180.0..180.0) return
        val p=TrailPoint(lat,lon,bearing,speedKmh,elapsedMs)
        // Compare against the last RECORDED breadcrumb, not inertialPoint:
        // inertialPoint is refreshed every 250 ms, so using it here
        // prevented any second breadcrumb from ever being recorded.
        inertialPoint=p
        val last=inertialTrail.lastOrNull()
        if(last!=null && elapsedMs-last.elapsedMs < 950L) return
        if(last!=null) {
            val dy=(lat-last.latitude)*111_195.0
            val dx=(lon-last.longitude)*111_195.0 *
                kotlin.math.cos(Math.toRadians(lat))
            val d=kotlin.math.hypot(dx,dy)
            val turn=kotlin.math.abs((((bearing-last.bearing+540)%360)+360)%360-180)
            if(d<0.65 && turn<3.0 && elapsedMs-last.elapsedMs<10_000L)return
        }
        inertialTrail.add(p)
        if(inertialTrail.size>1200) {
            // Keep a longer historical route without unbounded memory growth.
            val old=inertialTrail.take(600).filterIndexed { i,_->i%2==0 }
            val newest=inertialTrail.drop(600)
            inertialTrail.clear()
            inertialTrail.addAll(old)
            inertialTrail.addAll(newest)
        }
    }

    /** Diagnostic-only independent traces, never used to publish navigation fixes. */
    data class RawGpsPoint(
        val latitude:Double,val longitude:Double,val elapsedMs:Long,
        val approved:Boolean=false
    )
    val rawGpsTrail=ArrayList<RawGpsPoint>()
    val networkDrTrail=ArrayList<TrailPoint>()
    var networkDrPoint:TrailPoint?=null
        private set
    var diagnosticRunning=false
    var diagnosticText="Тест двух треков не запущен"
    var gpsSpoofStatus="GPS: ожидание контроля"
    var diagnosticNetworkStatus="Сетевых поправок пока нет"

    fun startDiagnostic(){
        rawGpsTrail.clear()
        networkDrTrail.clear()
        networkDrPoint=null
        diagnosticNetworkStatus="Сетевых поправок пока нет"
        diagnosticRunning=true
        diagnosticText="Режим GPS заглушён · DR использует OBD и Wi-Fi/сеть"
    }
    fun stopDiagnostic(){
        diagnosticRunning=false
        diagnosticText="Тест остановлен · результаты остаются на карте"
    }
    fun addRawGps(lat:Double,lon:Double,time:Long) {
        if(!diagnosticRunning || !lat.isFinite() || !lon.isFinite() ||
            lat !in -85.0..85.0 || lon !in -180.0..180.0)return
        val last=rawGpsTrail.lastOrNull()
        if(last!=null && time-last.elapsedMs<900)return
        rawGpsTrail.add(RawGpsPoint(lat,lon,time))
        if(rawGpsTrail.size>1500)rawGpsTrail.removeAt(0)
    }
    fun markRawGpsApproved(time:Long) {
        val i=rawGpsTrail.indexOfLast{it.elapsedMs==time}
        if(i>=0)rawGpsTrail[i]=rawGpsTrail[i].copy(approved=true)
    }
    fun addNetworkDrPoint(lat:Double,lon:Double,bearing:Double,
                           kmh:Double,time:Long){
        if(!diagnosticRunning || !lat.isFinite() || !lon.isFinite() ||
            !bearing.isFinite() || !kmh.isFinite() ||
            lat !in -85.0..85.0 || lon !in -180.0..180.0)return
        val p=TrailPoint(lat,lon,bearing,kmh,time)
        // Current pose updates at 4 Hz; historical points at ~1 Hz.
        // Never throttle against the live point, or the blue trail only
        // records its first sample and never becomes a line.
        networkDrPoint=p
        val last=networkDrTrail.lastOrNull()
        if(last!=null && time-last.elapsedMs<950)return
        networkDrTrail.add(p)
        if(networkDrTrail.size>1500)networkDrTrail.removeAt(0)
    }

    var message = "Ready"
    var speed: Int? = null
    var speedUpdatedElapsedMs: Long = 0L
    // Main navigator uses only the currently selected trusted coordinate source.
    // No fused mock readings are accepted as real position.
    data class NavFix(
        val lat:Double,val lon:Double,val bearing:Double?,
        val accuracy:Float,val source:String,val elapsedMs:Long
    )
    var navFix:NavFix?=null
    var networkFix:NavFix?=null
    var sourceDescription="Нет координат"
    var offlineUkraineStatus="Карта Украины / адреса: проверка файлов"
    var manualStart:NavigatorPrefs.ManualStart?=null
    var navigationDestination:Pair<Double,Double>?=null
    var activeRouting=false
    /**
     * Current user-selected road geometry for a 500 m GPS quality gate.
     * Route is never generated from unapproved raw GPS coordinates.
     * Empty when route guidance is not active.
     */
    @Volatile var gpsFilterRoutePoints:List<RoutingEngine.Point> = emptyList()

    var gpsSpeedKmh: Double? = null
    var gpsSpeedUpdatedElapsedMs: Long = 0L
    var gpsText = "Нет достоверного GPS"
    // FYT: external USB receiver arrives through another app's Android mock
    // provider. This application only CONSUMES it, never injects positions.
    var gpsActiveSource="Нет координат"
    var gpsInternalStatus="Ожидание"
    var gpsExternalStatus="Ожидание GM906BT"
    var gpsInternalUpdateMs=0L
    var gpsExternalUpdateMs=0L
    var gpsWakeStatus="Мониторинг запущен"

    var navigationMode = "GPS"
    var autoEnabled = true
    var manualForced = false
    var sensorAvailability = "Датчики: проверка"
    var motionText = "Датчики: ожидание"
    var accelerationText = "Акселерометр: ожидание"
    var compassStatus = "Компас: ожидание"
    var compassAccuracyText = "Магнитный датчик: ожидаем"
    var compassCalibrationText = "Проверка компаса не запускалась"
    var compassAlignmentText = "Курс автомобиля: не согласован"
    var calibrationText = "Нужна калибровка"
    var yawDegPerSec = 0.0
    // Live readings and verified calibration checkpoints published by MotionSensors.
    // They are distinct: sensor availability does NOT imply calibration success.
    var sensorGyroAvailable=false
    var sensorAccelAvailable=false
    var sensorLinearAccelAvailable=false
    var sensorGravityAvailable=false
    var sensorCompassAvailable=false
    var gyroCalibrated=false
    var accelCalibrated=false
    var compassMagnetCheckPassed=false
    var compassAligned=false
    var compassReliableNow=false
    var stationaryCalibrationRunning=false
    var compassCheckRunning=false
    var gyroSampleMs=0L
    var accelSampleMs=0L
    var compassSampleMs=0L
    var linearAccelerationMps2=Double.NaN
    var compassHeadingDeg=Double.NaN
    var compassFieldMicroTesla=Double.NaN
    var reverseSelected = false
    var directionConfirmed = false
    var directionText = "Направление: определяется"
    var obdFactor = 0.97
    fun obdSpeedKmh(): Double? = speed?.times(obdFactor)
    // Learning compares trusted GNSS only in the two-track test.
    // Corrections are opt-in; the default is never to alter inertial data.
    @Volatile var learnedDrEnabled=false
    @Volatile var learnedDrStatus="ИИ: обучение не начато"
    @Volatile var learnedDrDetail="Пока нет обучающих участков"

    var driftMeters = 0.0
    var position = "—"
    var deviceName = "No ELM327"
    var dr = false
    var mutMode = false
    var modeLabel = "OBD2 K-Line"
    val devices = linkedMapOf<String,String>()
}
