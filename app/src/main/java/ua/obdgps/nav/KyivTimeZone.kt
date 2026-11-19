package ua.obdgps.nav

import java.time.ZoneId
import java.time.ZoneOffset

/**
 * FYT UIS7870 Android 13 ROMs may ship outdated tzdata that recognizes
 * Europe/Kiev but not the modern Europe/Kyiv spelling.
 *
 * Resolve lazily, without a class-initialization crash on older firmware.
 * Prefer Ukraine's real DST rules; use a fixed winter offset only if the
 * system has neither the new nor the legacy Kyiv timezone database entry.
 */
internal object KyivTimeZone {
    fun resolve(lookup:(String)->ZoneId=ZoneId::of):ZoneId {
        for(id in listOf("Europe/Kyiv","Europe/Kiev","EET")) {
            val zone=runCatching{lookup(id)}.getOrNull()
            if(zone!=null)return zone
        }
        return ZoneOffset.ofHours(2)
    }
}
