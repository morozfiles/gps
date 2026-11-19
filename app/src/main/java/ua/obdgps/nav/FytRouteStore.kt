package ua.obdgps.nav

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Retains exact road geometry and active guidance across Android deep sleep,
 * process death and head unit restarts. Private to the FYT application ID.
 * A saved route never replaces or creates a mock Android GPS location.
 */
object FytRouteStore {
    data class Session(val destination:RoutingEngine.Point,
                       val route:RoutingEngine.Route?,
                       val active:Boolean)
    private var cached:Session?=null
    private var initialized=false

    @Synchronized fun load(context:Context):Session?{
        if(initialized)return cached
        initialized=true
        val file=AtomicFile(File(context.filesDir,"fyt-route.json.gz"))
        cached=runCatching {
            GZIPInputStream(file.openRead()).bufferedReader().use {
                val doc=JSONObject(it.readText())
                val lat=doc.getDouble("lat")
                val lon=doc.getDouble("lon")
                val dest=RoutingEngine.Point(lat,lon)
                if(!RoutingEngine.valid(dest))return@runCatching null
                val points=doc.optJSONArray("points")
                val route=if(points!=null && points.length() in 2..120_000) {
                    val shape=ArrayList<RoutingEngine.Point>(points.length())
                    for(i in 0 until points.length()){
                        val item=points.getJSONArray(i)
                        val p=RoutingEngine.Point(item.getDouble(0),item.getDouble(1))
                        if(!RoutingEngine.valid(p))error("Invalid route geometry")
                        shape.add(p)
                    }
                    val turns=ArrayList<RoutingEngine.Turn>()
                    val jsonTurns=doc.optJSONArray("turns")?:JSONArray()
                    for(i in 0 until minOf(jsonTurns.length(),10000)){
                        val t=jsonTurns.getJSONArray(i)
                        turns.add(RoutingEngine.Turn(
                            t.getInt(0).coerceIn(0,shape.lastIndex),
                            t.optString(1),t.optString(2),t.optInt(3)))
                    }
                    RoutingEngine.Route(shape,turns,
                        doc.optDouble("km",0.0),doc.optInt("seconds",0),dest)
                }else null
                Session(dest,route,doc.optBoolean("active",false) && route!=null)
            }
        }.getOrNull()
        return cached
    }

    @Synchronized fun saveDestination(context:Context,p:RoutingEngine.Point){
        if(!RoutingEngine.valid(p))return
        save(context,Session(p,null,false))
    }

    @Synchronized fun saveRoute(context:Context,route:RoutingEngine.Route,
                               active:Boolean=false){
        if(!RoutingEngine.valid(route.destination) ||
           route.points.size !in 2..120_000)return
        save(context,Session(route.destination,route,active))
    }

    @Synchronized fun setActive(context:Context,active:Boolean){
        val old=load(context)?:return
        save(context,old.copy(active=active && old.route!=null))
    }

    @Synchronized fun clear(context:Context){
        initialized=true;cached=null
        File(context.filesDir,"fyt-route.json.gz").delete()
        File(context.filesDir,"fyt-route.json.gz.new").delete()
    }

    private fun save(context:Context,session:Session){
        val doc=JSONObject()
            .put("lat",session.destination.lat)
            .put("lon",session.destination.lon)
            .put("active",session.active)
        session.route?.let { route ->
            doc.put("km",route.lengthKm).put("seconds",route.seconds)
            val arr=JSONArray()
            route.points.forEach { p->
                arr.put(JSONArray().put(p.lat).put(p.lon))
            }
            doc.put("points",arr)
            val turns=JSONArray()
            route.maneuvers.forEach{t->
                turns.put(JSONArray().put(t.shapeIndex)
                    .put(t.instruction).put(t.voice).put(t.type))
            }
            doc.put("turns",turns)
        }
        val atom=AtomicFile(File(context.filesDir,"fyt-route.json.gz"))
        var output:java.io.FileOutputStream?=null
        try {
            output=atom.startWrite()
            // GZIPOutputStream.close() would close the file before
            // AtomicFile.finishWrite, which must fsync it itself.
            val gzip=GZIPOutputStream(output)
            gzip.write(doc.toString().toByteArray(Charsets.UTF_8))
            gzip.finish()
            gzip.flush()
            atom.finishWrite(output)
            initialized=true;cached=session
        }catch(e:Exception){
            if(output!=null)atom.failWrite(output)
            android.util.Log.w("FytRouteStore","Cannot store route",e)
        }
    }
}
