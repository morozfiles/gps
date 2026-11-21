package ua.obdgps.nav

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** Runtime evidence: FYT devices and firmware revisions have different hardware. */
internal object FytHeadUnitProfile {
    data class Capabilities(
        val googleServices:Boolean,
        val gps:Boolean,
        val network:Boolean,
        val gyro:Boolean,
        val accelerometer:Boolean,
        val compass:Boolean,
        val systemBluetooth:Boolean
    ) {
        val inertialExplanation:String
            get()=if(gyro)"Гироскоп доступен"
                else "Гироскоп не обнаружен: OBD ведёт по линии маршрута, повороты оцениваются"
        val summary:String get()="FYT 7870 · GPS "+
            (if(gps)"✓" else "нет")+" · Wi-Fi/сеть "+
            (if(network)"✓" else "нет")+" · гироскоп "+
            (if(gyro)"✓" else "нет")+" · BT Android "+
            (if(systemBluetooth)"✓" else "нет")
    }
    fun read(context:Context):Capabilities {
        val sensors=context.getSystemService(SensorManager::class.java)
        val location=context.getSystemService(LocationManager::class.java)
        val providers=runCatching { location?.allProviders.orEmpty() }
            .getOrDefault(emptyList())
        val bt=runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter!=null
        }.getOrDefault(false)
        val gms=runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)==
                ConnectionResult.SUCCESS
        }.getOrDefault(false)
        return Capabilities(
            googleServices=gms,
            gps=LocationManager.GPS_PROVIDER in providers,
            network=LocationManager.NETWORK_PROVIDER in providers,
            gyro=sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!=null,
            accelerometer=sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null,
            compass=sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)!=null,
            systemBluetooth=bt
        )
    }
}
