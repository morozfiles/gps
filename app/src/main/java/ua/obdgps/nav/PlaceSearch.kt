package ua.obdgps.nav

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** One-shot explicit place search; no background autocomplete or bulk requests. */
class PlaceSearch {
    data class Result(val label:String,val coordinate:RoutingEngine.Point)
    private val worker=Executors.newSingleThreadExecutor{r->
        Thread(r,"Photon-Address-Search").apply{isDaemon=true}
    }
    private val main=Handler(Looper.getMainLooper())

    fun search(query:String,callback:(kotlin.Result<List<Result>>)->Unit) {
        val term=query.trim()
        if(term.length<3 || term.length>180) {
            callback(kotlin.Result.failure(
                IllegalArgumentException("Введите название или адрес (не менее 3 символов)")))
            return
        }
        worker.execute{
            val result=runCatching{
                val url=URL("https://photon.komoot.io/api/?q="+
                    URLEncoder.encode(term,"UTF-8")+"&limit=6&lang=ru")
                val conn=url.openConnection() as HttpURLConnection
                conn.connectTimeout=8000
                conn.readTimeout=10000
                conn.setRequestProperty("User-Agent",
                    "OBD-GPS-Navigator/0.9.0 (+https://github.com/morozfiles/gps)")
                try {
                    if(conn.responseCode!=200)throw IllegalStateException(
                        "Поиск адресов: HTTP "+conn.responseCode)
                    val body=conn.inputStream.bufferedReader().use{it.readText()}
                    if(body.length>1_000_000)throw IllegalStateException("Ответ слишком длинный")
                    val array=JSONObject(body).getJSONArray("features")
                    val found=ArrayList<Result>()
                    for(i in 0 until array.length()) {
                        val node=array.getJSONObject(i)
                        val properties=node.optJSONObject("properties")?:JSONObject()
                        val coords=node.getJSONObject("geometry").getJSONArray("coordinates")
                        val p=RoutingEngine.Point(coords.getDouble(1),coords.getDouble(0))
                        if(!RoutingEngine.valid(p))continue
                        val title=listOf("name","street","housenumber","city","state")
                            .map{properties.optString(it,"")}.filter{it.isNotBlank()}
                            .distinct().joinToString(", ").take(140)
                        found.add(Result(title.ifBlank{"Точка на карте"},p))
                    }
                    found
                }finally{conn.disconnect()}
            }
            main.post{callback(result)}
        }
    }
}
