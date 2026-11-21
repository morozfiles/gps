package ua.obdgps.nav

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.math.max

/**
 * Approximate positions are for map display and explicit start-point selection
 * only. They are NEVER approved GPS fixes, DR anchors or spoof-verification data.
 */
internal object ApproximateFixStore {
    private const val MAX_AGE_MS=10L*60L*1000L
    private const val MAX_RADIUS_METERS=100_000f

    fun usable(fix:State.NavFix,now:Long=SystemClock.elapsedRealtime()):Boolean {
        return fix.lat in -85.0..85.0 &&
            fix.lon in -180.0..180.0 &&
            fix.accuracy.isFinite() &&
            fix.accuracy in 1f..MAX_RADIUS_METERS &&
            now-fix.elapsedMs in 0L..MAX_AGE_MS &&
            !GpsSpoofGuard.isLima(fix.lat,fix.lon)
    }

    private fun score(fix:State.NavFix,now:Long):Double {
        val age=(now-fix.elapsedMs).coerceAtLeast(0L)/1000.0
        val sourcePenalty=when {
            fix.source.contains("Fused")->200.0
            fix.source.contains("Wi-Fi/сеть")->0.0
            else->500.0
        }
        return fix.accuracy.toDouble()+age*5.0+sourcePenalty
    }

    fun offer(location:Location,source:String):State.NavFix? {
        if(location.isMock || !location.hasAccuracy())return null
        val now=SystemClock.elapsedRealtime()
        val ageNanos=SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos
        if(ageNanos<0L || ageNanos>MAX_AGE_MS*1_000_000L)return null
        val fix=State.NavFix(location.latitude,location.longitude,
            location.bearing.takeIf{location.hasBearing()}?.toDouble(),
            location.accuracy,"Примерно · "+source,now-ageNanos/1_000_000L)
        if(!usable(fix,now))return null
        // Play-services fused and cached GPS can contain a spoofed GNSS fix.
        // When recent direct NETWORK_PROVIDER evidence disagrees by tens of
        // kilometres, keep the coarse network estimate rather than jumping.
        if(source.contains("Fused") || source.contains("GPS")) {
            val independent=State.approximateFix?.takeIf {
                it.source.contains("Wi-Fi/сеть") && !it.source.contains("Fused") &&
                    now-it.elapsedMs in 0L..120_000L && usable(it,now)
            }
            if(independent!=null) {
                val metres=RoutingEngine.meters(
                    RoutingEngine.Point(fix.lat,fix.lon),
                    RoutingEngine.Point(independent.lat,independent.lon))
                if(metres>max(5000.0,independent.accuracy*3.0+
                    fix.accuracy))return null
            }
        }
        val previous=State.approximateFix
        if(previous==null || !usable(previous,now) ||
            score(fix,now)<score(previous,now) ||
            fix.elapsedMs-previous.elapsedMs>60_000L) {
            State.approximateFix=fix
            return fix
        }
        return null
    }
}

/**
 * On entry, ask every Android/Play-services position source that can return
 * a quick approximate fix: direct network (Wi-Fi/cell), passive system cache,
 * cached GPS (not trusted), fused cache, and an active balanced-power request.
 * This uses OS location providers, NOT direct cell tower IDs or Wi-Fi SSIDs.
 */
internal class StartupPositionProbe(
    private val activity:Activity,
    private val onPosition:(State.NavFix)->Unit
) {
    private val lm=activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fused=LocationServices.getFusedLocationProviderClient(activity)
    private var token:CancellationTokenSource?=null

    fun start() {
        stop()
        val fine=activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
            PackageManager.PERMISSION_GRANTED
        val coarse=activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==
            PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse)return
        // Cached fixes are only displayed if Android reports a recent
        // timestamp. GPS caches are NOT accepted as validated GNSS.
        for(provider in listOf(LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,LocationManager.GPS_PROVIDER)) {
            if(provider==LocationManager.GPS_PROVIDER && !fine)continue
            try {
                lm.getLastKnownLocation(provider)?.let { candidate ->
                    offer(candidate,when(provider){
                        LocationManager.NETWORK_PROVIDER -> "Wi-Fi/сеть"
                        LocationManager.GPS_PROVIDER -> "GPS (кэш)"
                        else -> "системный кэш"
                    })
                }
            } catch(_:SecurityException){} catch(_:IllegalArgumentException){}
        }
        fused.lastLocation.addOnSuccessListener(activity) {
            it?.let{loc->offer(loc,"Fused (кэш)")}
        }
        val cancellation=CancellationTokenSource()
        token=cancellation
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            cancellation.token).addOnSuccessListener(activity) {
            it?.let{loc->offer(loc,"Fused Wi-Fi/сеть/GPS")}
        }.addOnFailureListener(activity) {
            // The NavService keeps independently searching GPS and NETWORK.
        }
    }

    private fun offer(location:Location,source:String){
        ApproximateFixStore.offer(location,source)?.let(onPosition)
    }
    fun stop(){
        token?.cancel()
        token=null
    }
}
