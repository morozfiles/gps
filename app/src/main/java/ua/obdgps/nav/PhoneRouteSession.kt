package ua.obdgps.nav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Keeps only the Google route selected by the driver, never a phone location history. */
internal class PhoneRouteSession(context:Context) {
    private val storage=context.getSharedPreferences("google_route_session_v1",Context.MODE_PRIVATE)

    data class Snapshot(
        val route:RoutingEngine.Route,
        val active:Boolean,
        val progressMeters:Double,
        val pose:RoutingEngine.Point?,
        val bearing:Double?
    )

    fun save(route:RoutingEngine.Route,active:Boolean,progressMeters:Double,
             pose:State.NavFix?) {
        if(route.points.size<2 || route.points.size>200_000)return
        val doc=JSONObject()
            .put("version",1)
            .put("active",active)
            .put("distance",progressMeters.coerceAtLeast(0.0))
            .put("lengthKm",route.lengthKm)
            .put("seconds",route.seconds)
            .put("destination",JSONArray().put(route.destination.lat).put(route.destination.lon))
            .put("updatedAt",System.currentTimeMillis())
        // Signed integer pairs; uses 1e-5 degree precision. Very small
        // storage footprint even for long Google routes. The selected
        // route is retained for reuse, not re-fetched on each resume.
        val points=JSONArray()
        for(p in route.points){
            points.put((p.lat*100000).toInt())
            points.put((p.lon*100000).toInt())
        }
        doc.put("p",points)
        val turns=JSONArray()
        for(t in route.maneuvers) {
            turns.put(JSONArray().put(t.shapeIndex).put(t.instruction)
                .put(t.voice).put(t.type))
        }
        doc.put("turns",turns)
        if(pose!=null && pose.lat.isFinite() && pose.lon.isFinite())
            doc.put("last",JSONArray().put(pose.lat).put(pose.lon)
                .put(pose.bearing?:JSONObject.NULL))
        storage.edit().putString("snapshot",doc.toString()).apply()
    }

    fun load():Snapshot? = runCatching {
        val raw=storage.getString("snapshot",null)?:return@runCatching null
        val obj=JSONObject(raw)
        if(obj.optInt("version")!=1)return@runCatching null
        val encoded=obj.getJSONArray("p")
        if(encoded.length()<4 || encoded.length()>400_000 || encoded.length()%2!=0)
            return@runCatching null
        val points=ArrayList<RoutingEngine.Point>(encoded.length()/2)
        var i=0
        while(i<encoded.length()) {
            val p=RoutingEngine.Point(encoded.getInt(i)/1e5,
                encoded.getInt(i+1)/1e5)
            if(!RoutingEngine.valid(p))return@runCatching null
            points.add(p)
            i+=2
        }
        val destination=obj.getJSONArray("destination").let {
            RoutingEngine.Point(it.getDouble(0),it.getDouble(1))
        }
        if(!RoutingEngine.valid(destination))return@runCatching null
        val turns=ArrayList<RoutingEngine.Turn>()
        val array=obj.optJSONArray("turns")?:JSONArray()
        for(n in 0 until array.length()) {
            val a=array.getJSONArray(n)
            val index=a.getInt(0)
            if(index !in points.indices)continue
            turns.add(RoutingEngine.Turn(index,
                a.getString(1),a.getString(2),a.getInt(3)))
        }
        val route=RoutingEngine.Route(points,turns,
            obj.getDouble("lengthKm"),obj.getInt("seconds"),destination)
        val last=obj.optJSONArray("last")
        val pose=last?.let {
            RoutingEngine.Point(it.getDouble(0),it.getDouble(1))
        }?.takeIf {RoutingEngine.valid(it)}
        val bearing=last?.optDouble(2,Double.NaN)?.takeIf {it.isFinite()}
        Snapshot(route,obj.optBoolean("active"),obj.optDouble("distance",0.0),
            pose,bearing)
    }.getOrNull()

    fun clear() {storage.edit().remove("snapshot").apply()}
}
