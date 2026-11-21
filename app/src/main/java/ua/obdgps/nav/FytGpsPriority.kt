package ua.obdgps.nav

/**
 * FYT head units may receive physical GNSS and a third-party GM906BT mock
 * through the same Android GPS_PROVIDER. The mock flag is an indicator of
 * injection, not proof that the source is actually GM906BT.
 */
internal object FytGpsPriority {
    const val EXTERNAL_HOLD_MS=4_500L
    fun process(isMock:Boolean,lastAcceptedExternalMs:Long,now:Long):Boolean =
        isMock || lastAcceptedExternalMs<=0L ||
            now-lastAcceptedExternalMs !in 0L..EXTERNAL_HOLD_MS
}
