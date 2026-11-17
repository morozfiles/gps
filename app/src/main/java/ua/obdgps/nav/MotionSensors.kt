package ua.obdgps.nav

import android.content.Context
import android.hardware.*
import android.os.SystemClock
import kotlin.math.*

/** Phone must be fixed relative to the vehicle. OBD distance + gravity-axis yaw rate. */
class MotionSensors(
    context: Context,
    private val onYaw: (Double, Double) -> Unit
) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val prefs = context.getSharedPreferences("motion", Context.MODE_PRIVATE)
    private val gravity = floatArrayOf(0f,0f,9.81f)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var gravityReady = false
    private var rotationReady = false
    private var lastGyroNs = 0L
    private var yawSmoothed = 0.0
    private var yawBias = prefs.getFloat("yawBias",0f).toDouble()
    private var accelBias = prefs.getFloat("accelBias",0f).toDouble()
    private var declination = 0.0
    private var magneticStrength = 0.0
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var trueNorth = 0.0
    private var carOffset: Double? =
        if(prefs.contains("carOffset")) prefs.getFloat("carOffset",0f).toDouble() else null
    private var calibrationStart=0L
    private var gyroSamples=0
    private var gyroSum=0.0
    private var accelSamples=0
    private var accelSum=0.0

    val compassReliable: Boolean get() =
        rotationReady && magneticStrength in 25.0..65.0 &&
            magneticAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    val compassBearing: Double? get() =
        if(compassReliable && carOffset != null) wrap360(trueNorth + carOffset!!) else null

    fun start() {
        val sensorNames = listOf(
            "Гироскоп" to Sensor.TYPE_GYROSCOPE,
            "Акселерометр" to Sensor.TYPE_ACCELEROMETER,
            "Компас" to Sensor.TYPE_ROTATION_VECTOR
        )
        State.sensorAvailability=sensorNames.joinToString(" · ") { p ->
            p.first + ": " + if(sm.getDefaultSensor(p.second)!=null) "✓" else "нет"
        }
        for(t in listOf(Sensor.TYPE_GYROSCOPE,Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_ROTATION_VECTOR,Sensor.TYPE_MAGNETIC_FIELD)) {
            sm.getDefaultSensor(t)?.let { sm.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME) }
        }
        State.calibrationText=if(prefs.contains("yawBias")) "Калибровка выполнена" else "Не откалибровано"
    }

    fun stop() = sm.unregisterListener(this)

    fun calibrate(speed: Int?, fresh: Boolean): String {
        if (!fresh || speed != 0) return "Останови автомобиль: OBD должен показывать 0"
        if(sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)==null || !gravityReady)
            return "Гироскоп или датчик гравитации недоступен"
        calibrationStart=SystemClock.elapsedRealtime()
        gyroSamples=0;gyroSum=0.0;accelSamples=0;accelSum=0.0
        State.calibrationText="Калибровка: не двигай телефон 5 секунд"
        return State.calibrationText
    }

    fun finishCalibration(): String {
        if(calibrationStart==0L) return State.calibrationText
        calibrationStart=0L
        if(gyroSamples<40 || accelSamples<20) {
            State.calibrationText="Мало данных: повтори калибровку"
            return State.calibrationText
        }
        val average=gyroSum/gyroSamples
        if(!average.isFinite() || abs(average)>0.14) {
            State.calibrationText="Обнаружено движение: повтори калибровку"
            return State.calibrationText
        }
        yawBias=average
        accelBias=accelSum/accelSamples
        prefs.edit().putFloat("yawBias",yawBias.toFloat())
            .putFloat("accelBias",accelBias.toFloat()).apply()
        State.calibrationText="Гироскоп и акселерометр откалиброваны"
        return State.calibrationText
    }

    fun setGeomagneticDeclination(location: android.location.Location) {
        declination=GeomagneticField(location.latitude.toFloat(),
            location.longitude.toFloat(),location.altitude.toFloat(),
            System.currentTimeMillis()).declination.toDouble()
    }

    /** Align the phone's magnetic Y-axis with travel heading on a straight GPS segment. */
    fun alignToGps(course: Double, kmh: Double, straight: Boolean) {
        if(!compassReliable || !straight || kmh < 12.0) return
        val target=wrapSigned(course - trueNorth)
        val previous=carOffset
        carOffset=if(previous == null) target
        else if(abs(wrapSigned(target-previous))<20.0)
            wrapSigned(previous+0.12*wrapSigned(target-previous))
        else previous
        prefs.edit().putFloat("carOffset",carOffset!!.toFloat()).apply()
    }

    fun alignNow(course: Double): String {
        if(!compassReliable) return "Компас недоступен или есть магнитные помехи"
        carOffset=wrapSigned(course-trueNorth)
        prefs.edit().putFloat("carOffset",carOffset!!.toFloat()).apply()
        return "Компас согласован с GPS"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if(sensor?.type==Sensor.TYPE_MAGNETIC_FIELD || sensor?.type==Sensor.TYPE_ROTATION_VECTOR)
            magneticAccuracy=accuracy
    }

    override fun onSensorChanged(event: SensorEvent) {
        when(event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                for(i in 0..2)gravity[i]=event.values[i]
                gravityReady=true
            }
            Sensor.TYPE_ACCELEROMETER -> if(!gravityReady) {
                for(i in 0..2)gravity[i]=.97f*gravity[i]+.03f*event.values[i]
                gravityReady=true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val norm=sqrt((0..2).sumOf { i->event.values[i].toDouble().pow(2) })
                if(calibrationStart!=0L) {accelSum+=norm;accelSamples++}
                val corrected=(norm-accelBias).coerceAtLeast(0.0)
                State.accelerationText=when{
                    corrected<0.25 -> "Ровное движение / стоим"
                    corrected<1.0 -> "Слабое ускорение или торможение"
                    else -> "Ускорение или торможение"
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticAccuracy=event.accuracy
                magneticStrength=sqrt((0..2).sumOf{i->event.values[i].toDouble().pow(2)})
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix,event.values)
                SensorManager.getOrientation(rotationMatrix,orientation)
                trueNorth=wrap360(Math.toDegrees(orientation[0].toDouble())+declination)
                State.compassStatus=if(compassBearing!=null)
                    "Курс " + compassBearing!!.toInt() + "°"
                    else "Компас: нет точного курса / помехи"
            }
            Sensor.TYPE_GYROSCOPE -> {
                val norm=sqrt(gravity.sumOf{it.toDouble()*it})
                if(norm < 7.0) return
                val raw=(0..2).sumOf{i->event.values[i].toDouble()*gravity[i]}/norm
                if(calibrationStart!=0L) {gyroSum+=raw;gyroSamples++}
                val dt=if(lastGyroNs>0L)(event.timestamp-lastGyroNs)*1e-9 else 0.0
                lastGyroNs=event.timestamp
                yawSmoothed=.82*yawSmoothed+.18*(raw-yawBias)
                val rightRate=-Math.toDegrees(yawSmoothed)
                State.yawDegPerSec=rightRate
                State.motionText=when {
                    State.speed == null || SystemClock.elapsedRealtime()-
                        State.speedUpdatedElapsedMs>3500L -> "Нет свежей скорости OBD"
                    State.speed == 0 -> "Стоим"
                    abs(rightRate) < 4.0 -> "Едем прямо"
                    rightRate > 0 -> "Поворот вправо"
                    else -> "Поворот влево"
                }
                if(dt in 0.001..0.15)onYaw(raw-yawBias,dt)
            }
        }
    }

    companion object {
        fun wrap360(v:Double)=((v%360)+360)%360
        fun wrapSigned(v:Double)=((v+540)%360+360)%360-180
    }
}
