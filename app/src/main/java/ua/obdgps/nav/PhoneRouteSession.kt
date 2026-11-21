package ua.obdgps.nav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

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
        val first=route.points.first()
        fun grid(x:Double)=(x*100000).roundToInt()
        val middle=route.points[route.points.size/2]
        val routeKey="${grid(first.lat)}:${grid(first.lon)}:"+
            "${grid(route.destination.lat)}:${grid(route.destination.lon)}:"+
            "${grid(middle.lat)}:${grid(middle.lon)}:${route.points.size}"
        val previous=runCatching {
            JSONObject(storage.getString("snapshot","{}")?:"{}")
        }.getOrDefault(JSONObject())
        val sameRoute=previous.optString("key")==routeKey
        val created=if(sameRoute)previous.optLong("createdAt",0L) else 0L
        // Google Routes geometry may only be cached temporarily. Never
        // retain/re-cache the same route beyond the maximum permitted age.
        if(sameRoute && created>0L &&
            System.currentTimeMillis()-created>=29L*24*60*60*1000) {
            clear()
            return
        }
        val doc=JSONObject().put("key",routeKey)
            .put("createdAt",if(created>0L)created else System.currentTimeMillis())
            .put("version",1)
            .put("active",active)
            .put("distance",progressMeters.coerceAtLeast(0.0))
            .put("destination",JSONArray().put(route.destination.lat).put(route.destination.lon))
            .put("updatedAt",System.currentTimeMillis())
        // Signed integer pairs; uses 1e-5 degree precision. Very small
        // storage footprint even for long Google routes. The selected
        // route is retained for reuse, not re-fetched on each resume.
        val points=JSONArray()
        for(p in route.points){
            points.put((p.lat*100000).roundToInt())
            points.put((p.lon*100000).roundToInt())
        }
        doc.put("p",points)
        if(pose!=null && pose.lat.isFinite() && pose.lon.isFinite())
            doc.put("last",JSONArray().put(pose.lat).put(pose.lon)
                .put(pose.bearing?:JSONObject.NULL))
        storage.edit().putString("snapshot",doc.toString()).apply()
    }

    fun load():Snapshot? = runCatching {
        val raw=storage.getString("snapshot",null)?:return@runCatching null
        val obj=JSONObject(raw)
        val created=obj.optLong("createdAt",0L)
        if(created<=0L || System.currentTimeMillis()-created>=29L*24*60*60*1000){
            clear()
            return@runCatching null
        }
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
        // Per Google Routes API caching terms only the coordinate
        // geometry is persisted. Turn text and duration remain memory-only;
        // on process restart online routing can repopulate them.
        var lengthMeters=0.0
        for(j in 1 until points.size)
            lengthMeters+=RoutingEngine.meters(points[j-1],points[j])
        val route=RoutingEngine.Route(points,emptyList(),
            lengthMeters/1000.0,(lengthMeters/13.9).toInt(),destination)
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
