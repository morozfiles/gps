package ua.obdgps.nav

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Address lookup when explicitly requested. The public Photon server may
 * reject lang=ru; omit it. Android system Geocoder is a fallback.
 * Raw coordinate input is resolved offline.
 */
class PlaceSearch(private val context:Context) {
    data class Result(val label:String,val coordinate:RoutingEngine.Point)
    private val worker=Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable,"Address-Search").apply { isDaemon=true }
    }
    private val main=Handler(Looper.getMainLooper())

    fun search(query:String,callback:(kotlin.Result<List<Result>>)->Unit) {
        val term=query.trim()
        val asCoordinates=parseCoordinates(term)
        if(asCoordinates!=null) {
            callback(kotlin.Result.success(listOf(Result(
                "Координаты: %.6f, %.6f".format(Locale.US,
                    asCoordinates.lat,asCoordinates.lon),asCoordinates))))
            return
        }
        if(term.length<3 || term.length>180) {
            callback(kotlin.Result.failure(IllegalArgumentException(
                "Введите адрес или координаты (например, 50.4501, 30.5234)")))
            return
        }
        worker.execute {
            val response=runCatching { photon(term) }
            val results=if(response.isSuccess && response.getOrNull()?.isNotEmpty()==true)
                response
            else {
                val native=runCatching { androidGeocoder(term) }
                if(native.isSuccess && native.getOrNull()?.isNotEmpty()==true)native
                else if(response.isFailure)response
                else native
            }
            main.post { callback(results) }
        }
    }

    private fun photon(query:String):List<Result> {
        val url=URL("https://photon.komoot.io/api/?q="+
            URLEncoder.encode(query,"UTF-8")+"&limit=8")
        val conn=url.openConnection() as HttpURLConnection
        conn.connectTimeout=9_000
        conn.readTimeout=11_000
        conn.instanceFollowRedirects=true
        conn.setRequestProperty("User-Agent",
            "OBD-GPS-Navigator/0.9.1 (Android; https://github.com/morozfiles/gps)")
        conn.setRequestProperty("Accept","application/json")
        conn.setRequestProperty("Accept-Language","en")
        try {
            val code=conn.responseCode
            if(code !in 200..299) {
                val explanation=when(code) {
                    400 -> "неверный запрос к серверу адресов"
                    403 -> "сервер отклонил запрос"
                    429 -> "сервер поиска временно ограничил запросы"
                    else -> "сервер поиска: HTTP $code"
                }
                throw IllegalStateException(explanation)
            }
            val raw=conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                val result=it.readText()
                if(result.length>2_000_000) throw IllegalStateException("Слишком длинный ответ")
                result
            }
            val array=JSONObject(raw).getJSONArray("features")
            val found=ArrayList<Result>()
            for(i in 0 until minOf(array.length(),30)) {
                val entry=array.getJSONObject(i)
                val props=entry.optJSONObject("properties")?:JSONObject()
                val xy=entry.getJSONObject("geometry").getJSONArray("coordinates")
                val point=RoutingEngine.Point(xy.getDouble(1),xy.getDouble(0))
                if(!RoutingEngine.valid(point))continue
                val title=listOf("name","street","housenumber","city","district",
                    "state","country").map { props.optString(it,"") }
                    .filter { it.isNotBlank() }.distinct().joinToString(", ").take(160)
                found.add(Result(title.ifBlank { "Точка на карте" },point))
            }
            return found.distinctBy { "%.5f:%.5f".format(Locale.US,
                it.coordinate.lat,it.coordinate.lon) }.take(8)
        } finally { conn.disconnect() }
    }

    @Suppress("DEPRECATION")
    private fun androidGeocoder(query:String):List<Result> {
        if(!Geocoder.isPresent())throw IllegalStateException(
            "Системная служба адресов Android недоступна")
        val geocoder=Geocoder(context,Locale.getDefault())
        val addresses=geocoder.getFromLocationName(query,8).orEmpty()
        val result=ArrayList<Result>()
        for(a in addresses) {
            if(!a.hasLatitude() || !a.hasLongitude())continue
            val p=RoutingEngine.Point(a.latitude,a.longitude)
            if(!RoutingEngine.valid(p))continue
            val caption=a.getAddressLine(0) ?: a.featureName ?: "Адрес на карте"
            result.add(Result(caption.take(160),p))
        }
        return result
    }

    private fun parseCoordinates(query:String):RoutingEngine.Point? {
        val regex=Regex("""^\s*([+-]?\d+(?:[.,]\d+)?)\s*[,;\s]\s*([+-]?\d+(?:[.,]\d+)?)\s*$""")
        val m=regex.matchEntire(query)?:return null
        val lat=m.groupValues[1].replace(",",".").toDoubleOrNull()?:return null
        val lon=m.groupValues[2].replace(",",".").toDoubleOrNull()?:return null
        return RoutingEngine.Point(lat,lon).takeIf { RoutingEngine.valid(it) }
    }
}
