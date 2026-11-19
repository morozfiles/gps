package ua.obdgps.nav

/** Priority among positions Android actually delivers, not hardware enumeration.
 * A GNSS USB app may replace GPS_PROVIDER rather than provide a separate stream.
 * A fresh accepted USB fix wins; otherwise system GNSS can take over.
 */
object FytGpsPriority {
    const val EXTERNAL_GRACE_MS=6500L
    fun externalPreferred(nowMs:Long,lastAcceptedExternalMs:Long):Boolean=
        lastAcceptedExternalMs>0L &&
            nowMs-lastAcceptedExternalMs in 0L..EXTERNAL_GRACE_MS

    /** Duplicated GM906BT fixes may be forwarded by GPS and Fused. */
    fun duplicateMockNanos(newFixNanos:Long,lastFixNanos:Long):Boolean=
        lastFixNanos>0L && newFixNanos>0L &&
            kotlin.math.abs(newFixNanos-lastFixNanos)<300_000_000L
}
