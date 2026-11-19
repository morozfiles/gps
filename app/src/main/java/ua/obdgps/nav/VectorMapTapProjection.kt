package ua.obdgps.nav

import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection

/**
 * Mapsforge 0.25's MapViewProjection.fromPixels() uses the previous
 * framebuffer position and does NOT undo MapView center offsets/rotation.
 * Use the live map position so a selected location corresponds to the
 * screen point where the user actually tapped.
 *
 * The inverse transformation is the same one used by
 * MapViewProjection.toPixels(latLong, true).
 */
internal object VectorMapTapProjection {
    fun toLatLong(
        x:Double, y:Double, width:Int, height:Int,
        center:LatLong, mapSize:Long, offsetX:Double, offsetY:Double,
        rotation:Rotation
    ):LatLong? {
        if(width<=0 || height<=0 || mapSize<=0L ||
            !x.isFinite() || !y.isFinite() ||
            x<0.0 || y<0.0 || x>width || y>height)return null
        return try {
            var pixel=Point(x-offsetX,y-offsetY)
            if(!Rotation.noRotation(rotation))pixel=rotation.rotate(pixel)
            val centerPx=MercatorProjection.longitudeToPixelX(
                center.longitude,mapSize)
            val centerPy=MercatorProjection.latitudeToPixelY(
                center.latitude,mapSize)
            val latitude=MercatorProjection.pixelYToLatitude(
                centerPy + pixel.y - height/2.0,mapSize)
            val longitude=MercatorProjection.pixelXToLongitude(
                centerPx + pixel.x - width/2.0,mapSize)
            if(!latitude.isFinite()||!longitude.isFinite()||
                latitude !in -85.0..85.0 || longitude !in -180.0..180.0)
                null else LatLong(latitude,longitude)
        } catch(_:IllegalArgumentException) {null}
    }
}
