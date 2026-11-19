package ua.obdgps.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log

/**
 * FYT Android 13 AOSP-compatible location reader. No Google Play services,
 * no FusedLocationProviderClient, no mock-location publishing.
 *
 * Android normally exposes the GM906BT companion app through mock
 * GPS_PROVIDER; PASSIVE_PROVIDER is also monitored for provider variations.
 */
class HybridLocator(
    private val context:Context,
    private val onRealLocation:(Location,String)->Unit,
    private val onExternalMockLocation:(Location)->Unit = {}
) {
    private val lm=context.getSystemService(Context.LOCATION_SERVICE) as?
        LocationManager

    private val networkListener=object:LocationListener {
        override fun onLocationChanged(location:Location){
            if(location.isMock)onExternalMockLocation(location)
            else onRealLocation(location,"Wi-Fi/сеть")
        }
    }
    private val passiveListener=object:LocationListener {
        override fun onLocationChanged(location:Location){
            // FYT firmware may deliver a mock via GPS or passive updates.
            // Real location is already received directly from GPS and NETWORK.
            if(location.isMock)onExternalMockLocation(location)
        }
    }
    private var active=false

    fun start(){
        if(active || lm==null ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
                PackageManager.PERMISSION_GRANTED)return
        active=true
        try{
            if(lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    3000L,0f,networkListener,Looper.getMainLooper())
            }
        }catch(e:Exception){Log.w("FytLocation","NETWORK provider unavailable",e)}
        try{
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                1000L,0f,passiveListener,Looper.getMainLooper())
        }catch(e:Exception){Log.w("FytLocation","Passive provider unavailable",e)}
    }

    fun stop(){
        active=false
        try{lm?.removeUpdates(networkListener)}catch(_:Exception){}
        try{lm?.removeUpdates(passiveListener)}catch(_:Exception){}
    }
}
