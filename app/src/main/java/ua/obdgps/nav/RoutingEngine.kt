package ua.obdgps.nav

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Road routing via the public Valhalla demonstration server.
 * Network required to CREATE/recalculate routes; a downloaded route stays
 * available while the app remains running without the internet.
 * Public demo service has rate limits and NO availability guarantee.
 */
open class RoutingEngine {
    data class Point(val lat:Double,val lon:Double)
    data class Turn(val shapeIndex:Int,val instruction:String,val voice:String,val type:Int)
    data class Progress(
        val nearestShapeIndex:Int,val lateralMeters:Double,
        val remainingMeters:Double,val secondsRemaining:Int,
        val nextTurn:Turn?,val nextTurnMeters:Double,val arrived:Boolean
    )
    class Route(
        val points:List<Point>,val maneuvers:List<Turn>,
        val lengthKm:Double,val seconds:Int,val destination:Point
    ) {
        private val suffix=DoubleArray(points.size)
        init {
            for(i in points.size-2 downTo 0)
                suffix[i]=suffix[i+1]+meters(points[i],points[i+1])
        }
        /** Progress by travelled route distance, not by globally nearest coordinates. */
        fun progressAt(alongMeters:Double,lateralMeters:Double=0.0):Progress {
            if(points.size<2)return Progress(0,lateralMeters,0.0,0,null,0.0,true)
            val total=suffix[0]
            val along=alongMeters.coerceIn(0.0,total)
            val remaining=total-along
            var lo=0;var hi=points.lastIndex
            while(lo+1<hi){
                val mid=(lo+hi)/2
                if(total-suffix[mid]<=along)lo=mid else hi=mid
            }
            val next=maneuvers.firstOrNull{
                it.shapeIndex in 0..points.lastIndex &&
                    total-suffix[it.shapeIndex]>along+2.0
            }
            val nextMeters=next?.let{
                (remaining-suffix[it.shapeIndex]).coerceAtLeast(0.0)
            }?:remaining
            val secondsLeft=if(total>0.0)(seconds*remaining/total).toInt() else 0
            return Progress(lo,lateralMeters,remaining,secondsLeft,next,nextMeters,
                remaining<40.0 && lateralMeters<60.0)
        }

        fun progress(position:Point):Progress {
            var minDist=Double.POSITIVE_INFINITY
            var nearest=0
            for(i in 0 until points.size-1) {
                val d=distanceToSegment(position,points[i],points[i+1])
                if(d<minDist){minDist=d;nearest=i}
            }
            val remaining=suffix.getOrElse(nearest){0.0}
            val next=maneuvers.firstOrNull{it.shapeIndex>nearest+1}
                ?: maneuvers.lastOrNull()
            val nextIndex=next?.shapeIndex?.coerceIn(0,points.lastIndex)?:points.lastIndex
            val nextMeters=(remaining-suffix[nextIndex]).coerceAtLeast(0.0)
            val secondsLeft=if(lengthKm>0.0)
                (seconds*remaining/(lengthKm*1000.0)).toInt() else 0
            val arrived=remaining<40 && meters(position,destination)<60
            return Progress(nearest,minDist,remaining,secondsLeft,
                next,nextMeters,arrived)
        }
    }

    private val executor=Executors.newSingleThreadExecutor{r->
        Thread(r,"Valhalla-Routes").apply{isDaemon=true}
    }
    private val main=Handler(Looper.getMainLooper())

    open fun request(from:Point,to:Point,callback:(Result<Route>)->Unit) {
        if(!valid(from)||!valid(to)) {
            callback(Result.failure(IllegalArgumentException("Некорректные координаты")))
            return
        }
        executor.execute {
            val result=runCatching{fetch(from,to)}
            main.post{callback(result)}
        }
    }

    private fun fetch(from:Point,to:Point):Route {
        val req=JSONObject()
            .put("locations",JSONArray()
                .put(JSONObject().put("lat",from.lat).put("lon",from.lon))
                .put(JSONObject().put("lat",to.lat).put("lon",to.lon)))
            .put("costing","auto")
            .put("units","kilometers")
            .put("language","ru-RU")
            .put("directions_type","instructions")
        val url=URL("https://valhalla1.openstreetmap.de/route")
        val conn=url.openConnection() as HttpURLConnection
        conn.requestMethod="POST"
        conn.connectTimeout=10_000
        conn.readTimeout=18_000
        conn.doOutput=true
        conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
        conn.setRequestProperty("Accept","application/json")
        conn.setRequestProperty("User-Agent","OBD-GPS-Navigator/0.9.0 (+https://github.com/morozfiles/gps)")
        conn.setRequestProperty("X-Client-Id","morozfiles.obd-gps-navigator")
        try {
            conn.outputStream.use{it.write(req.toString().toByteArray(Charsets.UTF_8))}
            val status=conn.responseCode
            val response=if(status in 200..299)conn.inputStream else conn.errorStream
            val body=response?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}
                ?: throw IllegalStateException("Сервер маршрутов недоступен (HTTP $status)")
            if(body.length>8_000_000)throw IllegalStateException("Слишком длинный маршрут")
            val root=JSONObject(body)
            if(status !in 200..299 || root.has("error_code"))
                throw IllegalStateException(root.optString("error","Сервис маршрутов: HTTP $status"))
            val trip=root.getJSONObject("trip")
            val legs=trip.getJSONArray("legs")
            if(legs.length()==0)throw IllegalStateException("Маршрут не найден")
            val summary=trip.getJSONObject("summary")
            val polyline=ArrayList<Point>()
            val steps=ArrayList<Turn>()
            for(i in 0 until legs.length()) {
                val leg=legs.getJSONObject(i)
                val decoded=decodePolyline6(leg.getString("shape"))
                if(decoded.isEmpty())continue
                val shift=polyline.size
                if(i>0 && polyline.isNotEmpty())polyline.removeAt(polyline.lastIndex)
                val startAt=polyline.size
                polyline.addAll(decoded)
                val maneuvers=leg.optJSONArray("maneuvers")?:JSONArray()
                for(k in 0 until maneuvers.length()) {
                    val m=maneuvers.getJSONObject(k)
                    val index=startAt+m.optInt("begin_shape_index",0)
                    steps.add(Turn(index,
                        m.optString("instruction","Продолжайте движение"),
                        m.optString("verbal_pre_transition_instruction",
                            m.optString("instruction","Продолжайте движение")),
                        m.optInt("type",0)))
                }
            }
            if(polyline.size<2 || polyline.size>120_000)
                throw IllegalStateException("Маршрут не содержит допустимую геометрию")
            return Route(polyline,steps,summary.getDouble("length"),
                summary.getDouble("time").roundToInt(),to)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun valid(p:Point)=p.lat.isFinite() && p.lon.isFinite() &&
            p.lat in -85.0..85.0 && p.lon in -180.0..180.0

        fun meters(a:Point,b:Point):Double {
            val y=Math.toRadians(b.lat-a.lat)
            val x=Math.toRadians(b.lon-a.lon)
            val latitude=Math.toRadians((a.lat+b.lat)/2)
            return 6371000.0*hypot(y,x*cos(latitude))
        }

        private fun distanceToSegment(p:Point,a:Point,b:Point):Double {
            val lat=Math.toRadians(p.lat)
            val ax=Math.toRadians(a.lon-p.lon)*cos(lat)*6371000
            val ay=Math.toRadians(a.lat-p.lat)*6371000
            val bx=Math.toRadians(b.lon-p.lon)*cos(lat)*6371000
            val by=Math.toRadians(b.lat-p.lat)*6371000
            val vx=bx-ax;val vy=by-ay
            val denom=vx*vx+vy*vy
            val t=if(denom<1.0)0.0 else
                (-(ax*vx+ay*vy)/denom).coerceIn(0.0,1.0)
            return hypot(ax+vx*t,ay+vy*t)
        }

        fun decodePolyline6(shape:String):List<Point> {
            val out=ArrayList<Point>()
            var i=0;var lat=0;var lon=0
            fun decode():Int {
                var value=0;var shift=0
                while(true) {
                    if(i>=shape.length || shift>30)throw IllegalArgumentException("Неверная геометрия")
                    val b=shape[i++].code-63
                    if(b<0)throw IllegalArgumentException("Неверная геометрия")
                    value=value or ((b and 0x1f) shl shift)
                    shift+=5
                    if(b<0x20)break
                }
                return if(value and 1!=0) (value shr 1).inv() else value shr 1
            }
            while(i<shape.length) {
                lat+=decode();lon+=decode()
                val p=Point(lat/1e6,lon/1e6)
                if(!valid(p))throw IllegalArgumentException("Ошибка координат маршрута")
                out.add(p)
                if(out.size>120_000)throw IllegalArgumentException("Слишком много точек")
            }
            return out
        }
    }
}
