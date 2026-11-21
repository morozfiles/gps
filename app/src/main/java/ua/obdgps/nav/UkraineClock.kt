package ua.obdgps.nav

import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Android 13 FYT firmwares may carry an older TZDB without the newer
 * Europe/Kyiv alias. Europe/Kiev is the same geographic IANA timezone
 * with the same historical / DST transitions.
 */
internal object UkraineClock {
    private val aliases=listOf("Europe/Kyiv","Europe/Kiev","EET")

    fun zone(lookup:(String)->ZoneId = { ZoneId.of(it) }):ZoneId {
        for(id in aliases) {
            try {
                return lookup(id)
            }catch(_:DateTimeException){}
        }
        // Last resort: local system timezone, then explicit Kyiv standard
        // offset. None of these fallbacks are allowed to crash the app.
        return try {
            ZoneId.systemDefault()
        }catch(_:Exception) {
            ZoneOffset.ofHours(2)
        }
    }
}
