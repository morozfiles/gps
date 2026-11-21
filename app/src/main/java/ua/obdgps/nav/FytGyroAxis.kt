package ua.obdgps.nav

/**
 * Optional mounted-FYT yaw axis when Android exposes a gyroscope but
 * NO gravity/accelerometer orientation. A portrait phone uses gravity
 * projection; an upright car head-unit usually has vehicle yaw on Y.
 */
internal object FytGyroAxis {
    val choices=listOf("Y+","Y-","X+","X-","Z+","Z-")
    fun project(values:FloatArray,selection:String):Double{
        val sign=if(selection.endsWith("-"))-1.0 else 1.0
        val axis=when(selection.firstOrNull()){
            'X'->0
            'Z'->2
            else->1
        }
        return sign*(values.getOrNull(axis)?.toDouble()?:0.0)
    }
}
