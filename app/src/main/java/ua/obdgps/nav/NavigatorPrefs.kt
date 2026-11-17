package ua.obdgps.nav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent user choices. Hardware calibration is retained separately in MotionSensors. */
class NavigatorPrefs(context: Context) {
    private val store=context.getSharedPreferences("navigator_preferences", Context.MODE_PRIVATE)

    var adapterKey: String?
        get()=store.getString("adapterKey",null)
        set(value){store.edit().putString("adapterKey",value).apply()}

    var adapterName: String?
        get()=store.getString("adapterName",null)
        set(value){store.edit().putString("adapterName",value).apply()}

    var obdFactor: Double
        get()=store.getFloat("obdFactor",0.96f).toDouble().coerceIn(0.70,1.30)
        set(value){
            if(value.isFinite() && value in 0.70..1.30)
                store.edit().putFloat("obdFactor",value.toFloat()).apply()
        }

    var autoEnabled: Boolean
        get()=store.getBoolean("autoEnabled",true)
        set(value){store.edit().putBoolean("autoEnabled",value).apply()}

    var mutMode: Boolean
        get()=store.getBoolean("mutMode",false)
        set(value){store.edit().putBoolean("mutMode",value).apply()}

    var protocolMode: String
        get() {
            val fallback=if(store.getBoolean("mutMode",false))"MUT" else "KLINE"
            return store.getString("protocolMode",fallback)?.takeIf {
                it in setOf("AUTO","KLINE","CAN","MUT")
            } ?: fallback
        }
        set(value) {
            if(value in setOf("AUTO","KLINE","CAN","MUT"))
                store.edit().putString("protocolMode",value).putBoolean("mutMode",value=="MUT").apply()
        }

    var alertRegionSlug:String
        get()=store.getString("alertRegionSlug","kyiv")?.takeIf { slug ->
            AirAlerts.regions.any {it.slug==slug}
        } ?: "kyiv"
        set(value) {
            if(AirAlerts.regions.any {it.slug==value})
                store.edit().putString("alertRegionSlug",value).apply()
        }

    data class RecentAddress(val label:String,val lat:Double,val lon:Double)

    fun recentAddresses():List<RecentAddress> {
        val results=ArrayList<RecentAddress>()
        val stored=store.getString("recentAddresses","[]")?:"[]"
        try {
            val array=JSONArray(stored)
            for(i in 0 until minOf(array.length(),5)) {
                val obj=array.optJSONObject(i)?:continue
                val label=obj.optString("label","").take(180)
                val lat=obj.optDouble("lat",Double.NaN)
                val lon=obj.optDouble("lon",Double.NaN)
                if(label.isNotBlank() && lat.isFinite() && lon.isFinite() &&
                    lat in -85.0..85.0 && lon in -180.0..180.0)
                    results.add(RecentAddress(label,lat,lon))
            }
        } catch(_:Exception) {}
        return results
    }

    fun rememberAddress(item:RecentAddress) {
        if(item.label.isBlank() || !item.lat.isFinite() || !item.lon.isFinite() ||
            item.lat !in -85.0..85.0 || item.lon !in -180.0..180.0)return
        val normalized=item.copy(label=item.label.trim().take(180))
        val updated=mutableListOf(normalized)
        updated.addAll(recentAddresses().filterNot {
            (kotlin.math.abs(it.lat-normalized.lat)<0.00001 &&
             kotlin.math.abs(it.lon-normalized.lon)<0.00001) ||
             it.label.equals(normalized.label,true)
        }.take(4))
        val array=JSONArray()
        for(entry in updated.take(5)) {
            array.put(JSONObject()
                .put("label",entry.label)
                .put("lat",entry.lat)
                .put("lon",entry.lon))
        }
        store.edit().putString("recentAddresses",array.toString()).apply()
    }

    data class ManualStart(val lat:Double,val lon:Double,val bearing:Double)
    var manualStart:ManualStart?
        get() {
            if(!store.contains("manualLat")||!store.contains("manualLon")||
               !store.contains("manualBearing"))return null
            val lat=Double.fromBits(store.getLong("manualLat",0L))
            val lon=Double.fromBits(store.getLong("manualLon",0L))
            val bearing=Double.fromBits(store.getLong("manualBearing",0L))
            return if(lat.isFinite()&&lon.isFinite()&&bearing.isFinite()&&
                lat in -85.0..85.0&&lon in -180.0..180.0)ManualStart(lat,lon,bearing)
                else null
        }
        set(value) {
            val edit=store.edit()
            if(value==null)edit.remove("manualLat").remove("manualLon").remove("manualBearing")
            else edit.putLong("manualLat",value.lat.toBits())
                .putLong("manualLon",value.lon.toBits())
                .putLong("manualBearing",value.bearing.toBits())
            edit.apply()
        }
}
