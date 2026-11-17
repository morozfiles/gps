package ua.obdgps.nav

import android.content.Context

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
        get()=store.getFloat("obdFactor",1.0f).toDouble().coerceIn(0.70,1.30)
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
}
