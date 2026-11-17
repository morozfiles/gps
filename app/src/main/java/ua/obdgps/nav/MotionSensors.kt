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
    private var gpsReverseCandidate: Boolean? = null
    private var gpsReverseCount=0
    private var gpsEvidenceLastMs=0L
    private var signedAccelerationFiltered=0.0
    private var stoppedSinceMs=0L
    private var wasStopped=true
    private var lastMotionSpeed=0.0
    private var magnetometerPresent=false
    private var magneticSampleMs=0L
    private var rotationHeadingAccuracyDeg=Double.NaN
    private var compassCheckStartMs=0L
    private var compassCheckSamples=0
    private val magnetMin=DoubleArray(3){Double.POSITIVE_INFINITY}
    private val magnetMax=DoubleArray(3){Double.NEGATIVE_INFINITY}
    private var lastAlignedCourse: Double?=null
    private var alignmentSamples=0
    private var lastAlignmentSampleMs=0L

    private fun accuracyLabel():String=when(magneticAccuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "высокая"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "средняя"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "низкая"
        else -> "недостоверная"
    }

    fun beginCompassCheck(obdKmh: Int?, obdFresh: Boolean):String {
        if(obdFresh && obdKmh != 0)
            return "Проверка компаса только на стоящем автомобиле"
        if(!magnetometerPresent || sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)==null)
            return "Нет магнитометра или датчика ориентации"
        compassCheckStartMs=SystemClock.elapsedRealtime()
        compassCheckSamples=0
        for(i in 0..2){magnetMin[i]=Double.POSITIVE_INFINITY;magnetMax[i]=Double.NEGATIVE_INFINITY}
        State.compassCalibrationText="Проверка 15 с: вращай телефон восьмёркой вдали от металла"
        return State.compassCalibrationText
    }

    private fun finishCompassCheck():String {
        compassCheckStartMs=0L
        val ranges=(0..2).count{i->
            magnetMin[i].isFinite() && magnetMax[i].isFinite() &&
                magnetMax[i]-magnetMin[i]>15.0
        }
        val message=when {
            compassCheckSamples<25 ->
                "Магнитометр не отвечает. Проверь датчик в другой программе"
            ranges<2 ->
                "Недостаточно поворотов: повтори восьмёрку в 3 измерениях"
            !compassReliable ->
                "Компас неточен: убери магнитный чехол и повтори вдали от металла"
            else ->
                "Магнитный датчик проверен. Закрепи телефон и привяжи курс к GPS"
        }
        State.compassCalibrationText=message
        return message
    }

    fun resetVehicleAlignment():String {
        carOffset=null
        prefs.edit().remove("carOffset").apply()
        alignmentSamples=0
        lastAlignmentSampleMs=0L
        lastAlignedCourse=null
        State.compassAlignmentText=
            "Жду 4 устойчивых GPS-курса: едь прямо от 15 км/ч"
        State.compassStatus="Компас: ожидает GPS-привязки"
        return State.compassAlignmentText
    }

    val compassReliable: Boolean get() =
        rotationReady && magnetometerPresent &&
            SystemClock.elapsedRealtime()-magneticSampleMs<3500L &&
            magneticStrength in 22.0..72.0 &&
            (magneticAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
                (rotationHeadingAccuracyDeg.isFinite() &&
                    rotationHeadingAccuracyDeg<25.0))
    val compassBearing: Double? get() =
        if(compassReliable && carOffset != null) wrap360(trueNorth + carOffset!!) else null

    fun start() {
        magnetometerPresent=sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)!=null
        State.compassCalibrationText="Магнитометр готов к проверке восьмёркой"
        State.compassAlignmentText=if(carOffset==null)
            "Курс автомобиля ещё не привязан. Доступна автопривязка по GPS"
            else "Привязка к автомобилю сохранена"
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

    /**
     * Align phone-to-vehicle heading after four consecutive real GPS courses
     * on a straight segment (NavService filters mock and speed disagreements).
     */
    fun alignToGps(course:Double,kmh:Double,straight:Boolean) {
        val now=SystemClock.elapsedRealtime()
        if(!compassReliable) {
            if(carOffset==null)State.compassAlignmentText=
                "Нет точного магнитного поля: выполни проверку компаса"
            alignmentSamples=0
            return
        }
        if(!straight || kmh<15.0 || !course.isFinite()) {
            if(carOffset==null)State.compassAlignmentText=
                "Для привязки едь прямо с хорошим GPS от 15 км/ч"
            alignmentSamples=0
            return
        }
        if(lastAlignmentSampleMs>0L && now-lastAlignmentSampleMs<550L)return
        val last=lastAlignedCourse
        if(now-lastAlignmentSampleMs>5000L ||
            (last!=null && abs(wrapSigned(course-last))>8.0))
            alignmentSamples=0
        lastAlignedCourse=course
        lastAlignmentSampleMs=now
        alignmentSamples++
        if(alignmentSamples<4) {
            if(carOffset==null)State.compassAlignmentText=
                "Привязка к GPS: $alignmentSamples/4 устойчивых измерений"
            return
        }
        val desired=wrapSigned(course-trueNorth)
        val previous=carOffset
        if(previous!=null && abs(wrapSigned(desired-previous))>=20.0) {
            State.compassAlignmentText=
                "Курс отличается от GPS: проверь крепление и магнитные помехи"
            return
        }
        carOffset=if(previous==null)desired
            else wrapSigned(previous+0.10*wrapSigned(desired-previous))
        prefs.edit().putFloat("carOffset",carOffset!!.toFloat()).apply()
        State.compassAlignmentText="Привязка компаса к автомобилю выполнена и сохранена"
    }

    /** Compare GPS travel course with calibrated physical vehicle heading. */
    fun directionFromGps(course: Double, speed: Double, now: Long): Boolean? {
        val body = compassBearing ?: return null
        if (!speed.isFinite() || speed < 7) return null
        val difference=abs(wrapSigned(course-body))
        val candidate=when {
            difference < 38.0 -> false
            difference > 142.0 -> true
            else -> null
        }
        if(candidate==null) {
            gpsReverseCandidate=null
            gpsReverseCount=0
            return null
        }
        if(now-gpsEvidenceLastMs>4500L || gpsReverseCandidate!=candidate) gpsReverseCount=0
        gpsEvidenceLastMs=now
        gpsReverseCandidate=candidate
        gpsReverseCount++
        return if(gpsReverseCount>=3) candidate else null
    }

    /**
     * Infer reverse only from a clear starting acceleration after a confirmed
     * stop and a previously calibrated phone-to-car orientation.
     * Braking and slopes make this heuristic uncertain; otherwise return null.
     */
    fun directionFromAcceleration(speed: Double, now: Long): Boolean? {
        if(!speed.isFinite()) return null
        if(speed<0.5) {
            if(!wasStopped) stoppedSinceMs=now
            if(stoppedSinceMs==0L) stoppedSinceMs=now
            wasStopped=true
            lastMotionSpeed=speed
            return null
        }
        val starting=wasStopped && now-stoppedSinceMs>1200L &&
            now-stoppedSinceMs<18000L && speed in 2.5..17.0 &&
            lastMotionSpeed<1.5
        lastMotionSpeed=speed
        wasStopped=false
        if(!starting || !compassReliable || carOffset==null) return null
        if(abs(signedAccelerationFiltered)<0.55) return null
        return signedAccelerationFiltered < 0.0
    }

    fun alignNow(course:Double):String {
        // A single GPS bearing cannot safely calibrate a mounted phone.
        return resetVehicleAlignment()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if(sensor?.type==Sensor.TYPE_MAGNETIC_FIELD)
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
                // Rotation matrix maps device acceleration to East/North/Up.
                val carBearing=compassBearing
                if(carBearing!=null) {
                    val east=rotationMatrix[0]*event.values[0]+
                        rotationMatrix[1]*event.values[1]+rotationMatrix[2]*event.values[2]
                    val north=rotationMatrix[3]*event.values[0]+
                        rotationMatrix[4]*event.values[1]+rotationMatrix[5]*event.values[2]
                    val angle=Math.toRadians(carBearing)
                    val forward=east*sin(angle)+north*cos(angle)
                    signedAccelerationFiltered=0.86*signedAccelerationFiltered+0.14*forward
                } else signedAccelerationFiltered=0.0
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
                rotationReady=true
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
