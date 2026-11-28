package ua.obdgps.nav

/** Only display measurements from a currently trusted, fresh source. */
internal data class SpeedDisplay(val kmh:Double?,val source:String) {
    val available:Boolean get()=kmh!=null
}

internal object NavigationSpeedSelector {
    fun choose(drActive:Boolean,gpsTrusted:Boolean,
               freshGpsKmh:Double?,freshObdKmh:Double?):SpeedDisplay {
        val gps=freshGpsKmh?.takeIf {it.isFinite() && it in 0.0..180.0}
        val obd=freshObdKmh?.takeIf {it.isFinite() && it in 0.0..180.0}
        return when {
            FytGpsFailover.speedMissingWhileMoving(gps,obd) -> SpeedDisplay(obd,"OBD")
            drActive && obd!=null -> SpeedDisplay(obd,"OBD")
            !drActive && gpsTrusted && gps!=null -> SpeedDisplay(gps,"GPS")
            obd!=null -> SpeedDisplay(obd,"OBD")
            gpsTrusted && gps!=null -> SpeedDisplay(gps,"GPS")
            else -> SpeedDisplay(null,"НЕТ ДАННЫХ")
        }
    }
}

/** FYT: no debounce when fresh OBD confirms motion and GNSS has no usable speed.
 * Silence is detected from the last actual accepted fix, not the UI republish.
 */
internal object FytGpsFailover {
    const val GPS_TIMEOUT_MS=2500L
    fun speedMissingWhileMoving(gps:Double?,obd:Double?):Boolean =
        obd!=null && obd.isFinite() && obd>0.0 && obd<=180.0 &&
            (gps==null || !gps.isFinite() || gps<=0.0 || gps>180.0)

    fun shouldUseObd(gps:Double?,obd:Double?,acceptedFixAgeMs:Long):Boolean =
        obd!=null && obd.isFinite() && obd>0.0 && obd<=180.0 &&
            (speedMissingWhileMoving(gps,obd) ||
                acceptedFixAgeMs !in 0L..GPS_TIMEOUT_MS)
}
