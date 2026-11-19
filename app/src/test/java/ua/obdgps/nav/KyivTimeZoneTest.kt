package ua.obdgps.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRulesException

class KyivTimeZoneTest {
    @Test fun modernAndroidUsesKyivIfSupported() {
        val selected=KyivTimeZone.resolve { name ->
            when(name) {
                "Europe/Kyiv" -> ZoneId.of("Europe/Kyiv")
                else -> error("should not try another zone")
            }
        }
        assertEquals("Europe/Kyiv",selected.id)
    }

    @Test fun oldFytAndroid13UsesHistoricalKievAlias() {
        val selected=KyivTimeZone.resolve { name ->
            if(name=="Europe/Kyiv")
                throw ZoneRulesException("Unknown time-zone ID: Europe/Kyiv")
            ZoneId.of(name)
        }
        assertEquals("Europe/Kiev",selected.id)
    }

    @Test fun noTimezoneDatabaseDoesNotCrashNavigator() {
        val selected=KyivTimeZone.resolve {
            throw ZoneRulesException("No tzdb entries")
        }
        assertEquals(ZoneOffset.ofHours(2),selected)
    }

    @Test fun ukraineDstRulesArePreservedWithKievAlias() {
        val zone=KyivTimeZone.resolve { name ->
            if(name=="Europe/Kyiv")
                throw ZoneRulesException("Unknown time-zone ID: Europe/Kyiv")
            ZoneId.of(name)
        }
        assertNotNull(zone.rules)
        // Same Ukraine DST rules in the old and modern tzdb IDs.
        assertEquals(3 * 3600,
            zone.rules.getOffset(java.time.Instant.parse("2026-07-01T12:00:00Z"))
                .totalSeconds)
        assertEquals(2 * 3600,
            zone.rules.getOffset(java.time.Instant.parse("2026-12-01T12:00:00Z"))
                .totalSeconds)
    }
}
