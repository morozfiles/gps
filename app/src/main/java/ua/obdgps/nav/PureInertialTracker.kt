package ua.obdgps.nav

/**
 * Quarantine heuristic for a GPS spoof that presents as an impossible
 * apparent relocation from Ukraine to the Lima, Peru metropolitan area.
 * This is only one of several plausibility checks and is not proof of intent.
 */
object GpsSpoofGuard {
    fun isLima(latitude:Double,longitude:Double):Boolean =
        latitude in -15.0..-9.0 && longitude in -80.0..-74.0

    fun plausibleRecovery(distanceMeters:Double,reportedSpeedKmh:Double?):Boolean =
        distanceMeters.isFinite() && distanceMeters<=250.0 &&
            reportedSpeedKmh!=null &&
            reportedSpeedKmh.isFinite() &&
            reportedSpeedKmh in 0.0..180.0
}
