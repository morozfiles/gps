package ua.obdgps.nav

/** Calibration is applied exactly once to decoded ECU speed (km/h). */
internal object ObdSpeedCalibration {
    const val DEFAULT_FACTOR=1.0
    fun correctedKmh(rawKmh:Int?,factor:Double=DEFAULT_FACTOR):Double? {
        if(rawKmh==null || rawKmh<0 || rawKmh>260 ||
            !factor.isFinite() || factor !in 0.70..1.30)return null
        return rawKmh*factor
    }
}
