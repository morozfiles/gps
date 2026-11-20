package ua.obdgps.nav

/**
 * Test-only weighting of real Android NETWORK_PROVIDER positions.
 * Network accuracy is evidence of quality; disagreement with dead reckoning
 * is NOT a reason to discard a correction in the diagnostic view.
 *
 * Returns a fraction of the positional offset to correct immediately.
 * No caller should apply this policy to GNSS/mock fixes or real navigation.
 */
internal object NetworkCorrectionPolicy {
    fun weight(reportedAccuracyMeters:Double):Double=when {
        !reportedAccuracyMeters.isFinite() || reportedAccuracyMeters<1.0 ||
            reportedAccuracyMeters>1000.0 -> 0.0
        reportedAccuracyMeters<=35.0 -> 0.90
        reportedAccuracyMeters<=80.0 -> 0.80
        reportedAccuracyMeters<=200.0 -> 0.65
        reportedAccuracyMeters<=500.0 -> 0.45
        else -> 0.25
    }
}
