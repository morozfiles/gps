package ua.obdgps.nav

/**
 * Traffic-light status for the four-panel FYT head-unit dashboard.
 *
 * These are monotonic timestamps (SystemClock.elapsedRealtime), never
 * wall-clock time. Stale samples and invalid values must be gray,
 * including speed 0 when the OBD/GNSS source has stopped reporting.
 *
 * GPS values MUST be written only after NavService accepts a position;
 * raw GNSS packets, including spoofed/rejected packets, are not eligible.
 */
internal object FytSpeedIndicators {
    data class Status(val valid:Boolean,val speedKmh:Double?)

    fun gpsStatus(kmh:Double?,receivedMs:Long,nowMs:Long):Status=
        of(kmh,receivedMs,nowMs,maxAgeMs=6000L,maxKmh=180.0)

    fun obdStatus(kmh:Double?,receivedMs:Long,nowMs:Long):Status=
        of(kmh,receivedMs,nowMs,maxAgeMs=4500L,maxKmh=260.0)

    private fun of(
        kmh:Double?,receivedMs:Long,nowMs:Long,
        maxAgeMs:Long,maxKmh:Double
    ):Status {
        val fresh=receivedMs>0L && nowMs-receivedMs in 0L..maxAgeMs
        val valid=fresh && kmh!=null && kmh.isFinite() &&
            kmh in 0.0..maxKmh
        return Status(valid,if(valid)kmh else null)
    }
}
