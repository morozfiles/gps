package ua.obdgps.nav

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.*

/** Official Google Routes API client. */
internal class GoogleRouteClient {
    private val background=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    fun request(from:RoutingEngine.Point,to:RoutingEngine.Point,
                done:(Result<RoutingEngine.Route>)->Unit) {
        background.execute {
            val result=runCatching { fetch(from,to) }
            main.post { done(result) }
        }
    }
    private fun fetch(a:RoutingEngine.Point,b:RoutingEngine.Point):RoutingEngine.Route {
        val key=BuildConfig.GOOGLE_ROUTES_KEY
        require(key.isNotBlank()){"Не задан GOOGLE_ROUTES_API_KEY"}
        fun location(p:RoutingEngine.Point)=JSONObject().put("location",
            JSONObject().put("latLng",JSONObject()
                .put("latitude",p.lat).put("longitude",p.lon)))
        val body=JSONObject().put("origin",location(a)).put("destination",location(b))
            .put("travelMode","DRIVE").put("routingPreference","TRAFFIC_UNAWARE")
            .put("languageCode","ru-RU")
        val c=URL("https://routes.googleapis.com/directions/v2:computeRoutes")
            .openConnection() as HttpURLConnection
        c.requestMethod="POST";c.connectTimeout=10000;c.readTimeout=20000;c.doOutput=true
        c.setRequestProperty("Content-Type","application/json")
        c.setRequestProperty("X-Goog-Api-Key",key)
        c.setRequestProperty("X-Goog-FieldMask","routes.distanceMeters,routes.duration,"+
            "routes.polyline.encodedPolyline,routes.legs.steps.navigationInstruction,"+
            "routes.legs.steps.polyline.encodedPolyline")
        return try {
            c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
            val response=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)
                ?.bufferedReader()?.use{it.readText().take(2000000)}?:""
            if(c.responseCode !in 200..299)
                error("Google Routes HTTP "+c.responseCode+": "+response.take(150))
            val r=JSONObject(response).getJSONArray("routes").getJSONObject(0)
            val points=decode(r.getJSONObject("polyline").getString("encodedPolyline"))
            require(points.size>=2){"Маршрут Google пуст"}
            val turns=ArrayList<RoutingEngine.Turn>()
            var minIndex=0
            val legs=r.optJSONArray("legs")
            if(legs!=null)for(li in 0 until legs.length()){
                val steps=legs.getJSONObject(li).optJSONArray("steps")?:continue
                for(si in 0 until steps.length()) {
                    val step=steps.getJSONObject(si)
                    val nav=step.optJSONObject("navigationInstruction")?:continue
                    val kind=nav.optString("maneuver")
                    if(kind=="DEPART" || kind=="DESTINATION")continue
                    val first=step.optJSONObject("polyline")
                        ?.optString("encodedPolyline")?.takeIf{it.isNotEmpty()}
                        ?.let{decode(it).firstOrNull()}
                    if(first!=null) {
                        var closest=Double.MAX_VALUE
                        for(i in minIndex until points.size){
                            val d=RoutingEngine.meters(points[i],first)
                            if(d<closest){closest=d;minIndex=i}
                        }
                    }
                    val instruction=nav.optString("instructions","Манёвр")
                        .replace(Regex("<[^>]*>")," ")
                    turns.add(RoutingEngine.Turn(minIndex,instruction,instruction,0))
                }
            }
            val seconds=r.optString("duration","0s").removeSuffix("s")
                .toDoubleOrNull()?.roundToInt()?:0
            RoutingEngine.Route(points,turns,
                r.getDouble("distanceMeters")/1000.0,seconds,b)
        } finally {c.disconnect()}
    }
    private fun decode(encoded:String):List<RoutingEngine.Point>{
        var lat=0;var lon=0;var i=0
        fun component():Int{
            var value=0;var shift=0
            while(true){
                require(i<encoded.length && shift<=30){"Некорректная полилиния"}
                val v=encoded[i++].code-63
                require(v>=0)
                value=value or ((v and 31) shl shift)
                shift+=5
                if(v<32)break
            }
            return if(value and 1==1)(value shr 1).inv() else value shr 1
        }
        val result=ArrayList<RoutingEngine.Point>()
        while(i<encoded.length){
            lat+=component();lon+=component()
            result.add(RoutingEngine.Point(lat/1e5,lon/1e5))
            require(result.size<200000){"Слишком длинный маршрут"}
        }
        return result
    }
}