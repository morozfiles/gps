package ua.obdgps.nav

/** GPS-loss auto DR policy for a phone with a route and a live OBD speed.
 * Air alerts accelerate the switch but must NOT be mandatory for navigation.
 * No GPS spoof/route verification happens here; NavService owns that.
 */
internal object MobileDrAutoStart {
    fun eligible(
        autoEnabled:Boolean,alreadyDr:Boolean,routeActive:Boolean,
        alertActive:Boolean,freshObd:Boolean,obdKmh:Double,
        badGps:Boolean
    ):Boolean {
        if(!autoEnabled || alreadyDr || !freshObd ||
            !obdKmh.isFinite() || !badGps)return false
        return if(routeActive)obdKmh>=2.5
            else alertActive && obdKmh>=7.0
    }
    fun debounceMs(recentTeleport:Boolean):Long=
        if(recentTeleport)1500L else 5000L
}
