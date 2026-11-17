package ua.obdgps.nav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

/**
 * Inject only into Google Play services' fused provider, not into the Android
 * hardware GPS_PROVIDER. Direct LocationManager GPS observations can therefore
 * continue independently, if the phone/firmware keeps GNSS running.
 */
class FusedMockRelay(context: Context) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    var active = false
        private set
    private var starting = false
    private var publishBusy = false

    fun start(callback: (Boolean, String) -> Unit) {
        if(active) { callback(true, "Fused mock включён");return }
        if(starting) return
        starting=true
        client.setMockMode(true)
            .addOnSuccessListener {
                starting=false
                active=true
                callback(true,"Fused mock включён")
            }
            .addOnFailureListener { e ->
                starting=false
                active=false
                callback(false,"Mock Location недоступен: " + (e.message ?: "ошибка"))
            }
    }

    fun publish(lat: Double, lon: Double, bearing: Double, kmh: Int, accuracy: Float) {
        if(!active || publishBusy || !lat.isFinite() || !lon.isFinite())return
        val loc = Location("fused").apply {
            latitude=lat
            longitude=lon
            this.bearing=bearing.toFloat()
            speed=(kmh.coerceIn(0,255)/3.6f)
            this.accuracy=accuracy.coerceAtLeast(8f)
            time=System.currentTimeMillis()
            elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
        }
        publishBusy=true
        client.setMockLocation(loc)
            .addOnCompleteListener { task ->
                publishBusy=false
                if(!task.isSuccessful) {
                    State.message="Не удалось обновить Fused Location: " +
                        (task.exception?.localizedMessage ?: "ошибка")
                }
            }
    }

    fun stop() {
        active=false
        starting=false
        publishBusy=false
        client.setMockMode(false).addOnFailureListener { e ->
            State.message="Отключение Mock Location: " + (e.localizedMessage ?: "ошибка")
        }
    }
}
