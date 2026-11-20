package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import kotlin.math.abs

class VectorMapTapProjectionTest {
    @Test fun repeatedMapTapsResolveToVisiblePointsAtVariousRotations() {
        val center=LatLong(50.4501,30.5234)
        val w=1100
        val h=700
        val zoom=17.toByte()
        val size=MercatorProjection.getMapSize(zoom,256)
        val offsetX=0.0
        val offsetY=h*0.18
        val testTaps=listOf(
            170.0 to 160.0, 420.0 to 210.0,
            720.0 to 260.0, 910.0 to 400.0,
            330.0 to 530.0, 790.0 to 550.0
        )
        for(angle in listOf(0f,35f,-70f)){
            val rotation=Rotation(angle,w/2f,h*0.68f)
            for((x,y) in testTaps){
                val geo=VectorMapTapProjection.toLatLong(
                    x,y,w,h,center,size,offsetX,offsetY,rotation)
                assertNotNull("missing map point at ($x,$y), rotation=$angle",geo)
                val centerX=MercatorProjection.longitudeToPixelX(center.longitude,size)
                val centerY=MercatorProjection.latitudeToPixelY(center.latitude,size)
                var screen=Point(
                    MercatorProjection.longitudeToPixelX(geo!!.longitude,size)-centerX+w/2.0,
                    MercatorProjection.latitudeToPixelY(geo.latitude,size)-centerY+h/2.0)
                if(!Rotation.noRotation(rotation))
                    screen=rotation.rotate(screen,true)
                screen=screen.offset(offsetX,offsetY)
                assertEquals("marker X at angle $angle",x,screen.x,0.02)
                assertEquals("marker Y at angle $angle",y,screen.y,0.02)
            }
        }
    }

    @Test fun ignoresOutOfBoundsAndInvalidMapTaps(){
        val center=LatLong(50.4501,30.5234)
        val rotation=Rotation.NULL_ROTATION
        assertNull(VectorMapTapProjection.toLatLong(
            -1.0,50.0,100,100,center,1024L,0.0,0.0,rotation))
        assertNull(VectorMapTapProjection.toLatLong(
            50.0,101.0,100,100,center,1024L,0.0,0.0,rotation))
        assertNull(VectorMapTapProjection.toLatLong(
            Double.NaN,20.0,100,100,center,1024L,0.0,0.0,rotation))
        assertNull(VectorMapTapProjection.toLatLong(
            50.0,50.0,100,100,center,0L,0.0,0.0,rotation))
    }
}
