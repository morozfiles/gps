package ua.obdgps.nav

import kotlin.math.*

object Obd {
    private val pattern = Regex("410D([0-9A-F]{2})")
    fun parseSpeed(s: String): Int? {
        val cleaned = s.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        return pattern.find(cleaned)?.groupValues?.get(1)?.toIntOrNull(16)
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
    var position = "—"
    var deviceName = "No ELM327"
    var dr = false
    val devices = linkedMapOf<String,String>()
}
