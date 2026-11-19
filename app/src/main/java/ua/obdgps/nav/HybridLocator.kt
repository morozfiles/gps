package ua.obdgps.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Android system network location (Wi-Fi/cellular and eligible BLE beacons)
 * + Google Play services fused location. No raw BLE MAC-based geolocation:
 * that requires a surveyed beacon database.
 */
class HybridLocator(
    private val context:Context,
    private val onRealLocation:(Location,String)->Unit,
    private val onExternalMockLocation:(Location)->Unit = {}
) {
    private val lm=context.getSystemService(LocationManager::class.java)
    private val fused=runCatching{
        LocationServices.getFusedLocationProviderClient(context)
    }.getOrNull()
    private val networkListener=object:LocationListener {
        override fun onLocationChanged(location:Location) {
            if(location.isMock)onExternalMockLocation(location)
            else onRealLocation(location,"Wi-Fi/сеть")
        }
    }
    // Works even on FYT firmware without Google Play services: a second
    // mock provider can be observed passively when another app publishes it.
    private val passiveListener=object:LocationListener {
        override fun onLocationChanged(location:Location){
            if(location.isMock)onExternalMockLocation(location)
        }
    }
    private val fusedListener=object:LocationCallback() {
        override fun onLocationResult(result:LocationResult) {
            for(location in result.locations) {
                // Android marks our injected fused positions as mock.
                // Real network/Wi-Fi/cellular/eligible BLE fixes are useful
                // for a gently bounded DR correction too.
                if(location.isMock)onExternalMockLocation(location)
                else onRealLocation(location,"Fused Android")
            }
        }
    }
    private var active=false

    fun start() {
        if(active || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            !=PackageManager.PERMISSION_GRANTED)return
        active=true
        try {
            if(lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    3000L,0f,networkListener,Looper.getMainLooper())
            }
        } catch(_:Exception){}
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                1000L,0f,passiveListener,Looper.getMainLooper())
        }catch(_:Exception){}
        try {
            val request=LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                3000L).setMinUpdateIntervalMillis(1500L).build()
            fused?.requestLocationUpdates(request,fusedListener,Looper.getMainLooper())
        } catch(_:Exception){}
    }

    fun stop() {
        active=false
        try{lm.removeUpdates(networkListener)}catch(_:Exception){}
        try{lm.removeUpdates(passiveListener)}catch(_:Exception){}
        try{fused?.removeLocationUpdates(fusedListener)}catch(_:Exception){}
    }
}
