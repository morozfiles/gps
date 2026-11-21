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
            drActive && obd!=null -> SpeedDisplay(obd,"OBD")
            !drActive && gpsTrusted && gps!=null -> SpeedDisplay(gps,"GPS")
            obd!=null -> SpeedDisplay(obd,"OBD")
            gpsTrusted && gps!=null -> SpeedDisplay(gps,"GPS")
            else -> SpeedDisplay(null,"НЕТ ДАННЫХ")
        }
    }
}
