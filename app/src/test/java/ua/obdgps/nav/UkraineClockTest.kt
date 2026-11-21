package ua.obdgps.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.zone.ZoneRulesException
import java.time.LocalDate

class UkraineClockTest {
    @Test fun prefersCurrentNameWhenAvailable() {
        assertEquals("Europe/Kyiv",UkraineClock.zone().id)
    }

    @Test fun oldAndroidTzdbFallsBackToEuropeKiev() {
        val zone=UkraineClock.zone { name->
            if(name=="Europe/Kyiv")
                throw ZoneRulesException("Unknown time-zone ID: Europe/Kyiv")
            ZoneId.of(name)
        }
        assertEquals("Europe/Kiev",zone.id)
    }

    @Test fun oldKievRulesPreserveSummerWinterTime() {
        val zone=ZoneId.of("Europe/Kiev")
        val winter=LocalDate.of(2026,1,15).atStartOfDay(zone).offset.totalSeconds
        val summer=LocalDate.of(2026,7,15).atStartOfDay(zone).offset.totalSeconds
        assertEquals(2*3600,winter)
        assertEquals(3*3600,summer)
    }

    @Test fun bothRegionAliasesMissingStillDoesNotCrash() {
        val zone=UkraineClock.zone { name->
            if(name=="Europe/Kyiv" || name=="Europe/Kiev")
                throw ZoneRulesException("Unsupported $name")
            ZoneId.of(name)
        }
        assertTrue(zone.id.isNotEmpty())
    }
}
