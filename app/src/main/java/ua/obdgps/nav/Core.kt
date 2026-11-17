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
    fun move(kmh: Int, dt: Double) {
        if (dt <= 0 || dt > 1 || kmh !in 0..255) return
        val p = pose ?: return
        val dist = (kmh / 3.6) * dt / 6371000
        val a = Math.toRadians(p.lat)
        val b = Math.toRadians(p.lon)
        val heading = Math.toRadians(p.bearing)
        val nextLat = asin((sin(a) * cos(dist) + cos(a) * sin(dist) * cos(heading)).coerceIn(-1.0,1.0))
        val nextLon = b + atan2(sin(heading) * sin(dist) * cos(a), cos(dist)-sin(a)*sin(nextLat))
        pose = p.copy(lat = Math.toDegrees(nextLat), lon = (Math.toDegrees(nextLon) + 540) % 360 - 180)
    }
}
object State {
    var message = "Ready"
    var speed: Int? = null
    var speedUpdatedElapsedMs: Long = 0L
    var gpsSpeedKmh: Double? = null
    var gpsSpeedUpdatedElapsedMs: Long = 0L
    var gpsText = "Нет достоверного GPS"
    var navigationMode = "GPS"
    var autoEnabled = true
    var manualForced = false
    var sensorAvailability = "Датчики: проверка"
    var motionText = "Датчики: ожидание"
    var accelerationText = "Акселерометр: ожидание"
    var compassStatus = "Компас: ожидание"
    var calibrationText = "Нужна калибровка"
    var yawDegPerSec = 0.0
    var reverseSelected = false
    var directionText = "Вперёд (предположительно)"
    var driftMeters = 0.0
    var position = "—"
    var deviceName = "No ELM327"
    var dr = false
    var mutMode = false
    var modeLabel = "OBD2 K-Line"
    val devices = linkedMapOf<String,String>()
}
