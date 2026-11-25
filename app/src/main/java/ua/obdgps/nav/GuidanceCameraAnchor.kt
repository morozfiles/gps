package ua.obdgps.nav

import kotlin.math.*

/**
 * Keep the driving arrow low on screen, but ABOVE FYT's bottom notification
 * banner. The map projection supplies the geographic camera correction.
 */
internal object GuidanceCameraAnchor {
    fun markerY(viewHeightPx:Int,noticeTopPx:Int,clearancePx:Int):Int {
        if(viewHeightPx<=0)return 0
        val preferred=viewHeightPx*0.69
        val safeLimit=(noticeTopPx-clearancePx).toDouble()
        return min(preferred,safeLimit)
            .coerceIn(viewHeightPx*0.44,viewHeightPx*0.74).roundToInt()
    }

    /** Geographic delta of the camera target toward a fixed marker pixel. */
    fun shiftedTarget(currentTarget:RoutingEngine.Point,
                      marker:RoutingEngine.Point,
                      geoAtDesiredPixel:RoutingEngine.Point,
                      gain:Double=0.30):RoutingEngine.Point {
        val a=gain.coerceIn(0.0,1.0)
        val deltaLat=(marker.lat-geoAtDesiredPixel.lat)*a
        val deltaLon=(((marker.lon-geoAtDesiredPixel.lon+540.0)%360.0)-180.0)*a
        return RoutingEngine.Point(
            (currentTarget.lat+deltaLat).coerceIn(-85.0,85.0),
            ((currentTarget.lon+deltaLon+540.0)%360.0)-180.0)
    }
}
