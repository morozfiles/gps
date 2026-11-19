package ua.obdgps.nav

import java.util.Locale

/** A single UI truth source: green checkmarks never use the availability text. */
internal enum class CalibrationSensor { COMPASS, GYROSCOPE, ACCELEROMETER }

internal data class SensorCalibrationSnapshot(
    val gyroAvailable:Boolean=false,
    val accelAvailable:Boolean=false,
    val linearAccelAvailable:Boolean=false,
    val gravityAvailable:Boolean=false,
    val compassAvailable:Boolean=false,
    val gyroCalibrated:Boolean=false,
    val accelCalibrated:Boolean=false,
    val compassCheckPassed:Boolean=false,
    val compassAligned:Boolean=false,
    val compassReliable:Boolean=false,
    val gyroFresh:Boolean=false,
    val accelFresh:Boolean=false,
    val compassFresh:Boolean=false,
    val calibrationRunning:Boolean=false,
    val compassChecking:Boolean=false,
    val yawDegreesPerSecond:Double=Double.NaN,
    val accelerationMetersPerSecond2:Double=Double.NaN,
    val headingDegrees:Double=Double.NaN,
    val fieldMicroTesla:Double=Double.NaN,
    val calibrationMessage:String="",
    val compassCheckMessage:String="",
    val compassAlignmentMessage:String=""
)

internal object SensorCalibrationCards {
    /** The existing 5-second stationary calibration commits BOTH biases together. */
    private fun stationaryPassed(s:SensorCalibrationSnapshot)=
        s.gyroAvailable && s.accelAvailable && s.linearAccelAvailable &&
            s.gravityAvailable && s.gyroCalibrated && s.accelCalibrated &&
            !s.calibrationRunning

    fun checked(sensor:CalibrationSensor,s:SensorCalibrationSnapshot):Boolean =
        when(sensor) {
            CalibrationSensor.GYROSCOPE,CalibrationSensor.ACCELEROMETER ->
                stationaryPassed(s)
            CalibrationSensor.COMPASS -> s.compassAvailable &&
                s.compassCheckPassed && s.compassAligned &&
                s.compassReliable && s.compassFresh && !s.compassChecking
        }

    private fun number(value:Double,suffix:String,decimals:Int=1):String =
        if(value.isFinite())
            String.format(Locale.US,"%.${decimals}f%s",value,suffix)
        else "Нет данных"

    fun buttonText(sensor:CalibrationSensor,s:SensorCalibrationSnapshot):String {
        val yes=if(checked(sensor,s))" ✓" else ""
        return when(sensor) {
            CalibrationSensor.COMPASS -> {
                val reading=if(!s.compassAvailable)"Нет датчика"
                    else if(!s.compassFresh)"Нет сигнала"
                    else number(s.headingDegrees,"°",0)
                val field=if(s.compassFresh && s.fieldMicroTesla.isFinite())
                    number(s.fieldMicroTesla," мкТ",0) else "Ожидание"
                "Компас$yes\n$reading\n$field"
            }
            CalibrationSensor.GYROSCOPE -> {
                val reading=if(!s.gyroAvailable)"Нет датчика"
                    else if(!s.gyroFresh)"Нет сигнала"
                    else number(s.yawDegreesPerSecond,"°/с")
                "Гироскоп$yes\n$reading"
            }
            CalibrationSensor.ACCELEROMETER -> {
                val reading=if(!s.accelAvailable || !s.linearAccelAvailable)
                    "Нет датчика"
                    else if(!s.accelFresh)"Нет сигнала"
                    else number(s.accelerationMetersPerSecond2," м/с²")
                "Акселерометр$yes\n$reading"
            }
        }
    }

    fun hint(sensor:CalibrationSensor,s:SensorCalibrationSnapshot):String {
        return when(sensor) {
            CalibrationSensor.COMPASS -> when {
                !s.compassAvailable ->
                    "Компас: нужен магнитометр и датчик ориентации Android."
                s.compassChecking ->
                    "Компас: идёт проверка 15 с. Вращай телефон восьмёркой во всех плоскостях."
                !s.compassCheckPassed ->
                    "Компас: на стоящем авто выполни восьмёрку 15 с вдали от металла. " +
                        "Нужны 25+ измерений, изменение поля по 2 осям и точный компас. " +
                        problem(s.compassCheckMessage)
                !s.compassReliable || !s.compassFresh ->
                    "Компас: магнитные помехи или недостоверные данные. Убери магнитный " +
                        "держатель и проверь поле и точность, затем повтори проверку."
                !s.compassAligned ->
                    "Компас: магнитный датчик проверен, но курс авто не привязан. " +
                        "Закрепи телефон; с хорошим GPS едь прямо от 15 км/ч — " +
                        "нужны 4 устойчивых измерения. " +
                        problem(s.compassAlignmentMessage)
                else -> "Компас: ✓ проверка восьмёркой и GPS-привязка выполнены, данные достоверны."
            }
            CalibrationSensor.GYROSCOPE,CalibrationSensor.ACCELEROMETER -> {
                val name=if(sensor==CalibrationSensor.GYROSCOPE)"Гироскоп" else "Акселерометр"
                when {
                    !s.gyroAvailable || !s.gravityAvailable ->
                        "$name: нужен гироскоп и датчик гравитации."
                    !s.accelAvailable || !s.linearAccelAvailable ->
                        "$name: отсутствует акселерометр или линейное ускорение."
                    s.calibrationRunning ->
                        "$name: калибровка идёт 5 с. Не двигай телефон и автомобиль."
                    stationaryPassed(s) ->
                        "$name: ✓ успешно. Калибровка гироскопа и акселерометра сохранена."
                    else ->
                        "$name: останови авто (свежий OBD = 0 км/ч), " +
                            "не двигай телефон 5 с. Нужно 40+ замеров гироскопа, " +
                            "20+ замеров ускорения и отсутствие вращения. " +
                            problem(s.calibrationMessage)
                }
            }
        }
    }

    private fun problem(message:String):String =
        if(message.isBlank() || message=="Не откалибровано" ||
            message=="Нужна калибровка" ||
            message.startsWith("Магнитометр готов")) ""
        else "Статус: "+message.take(140)
}
