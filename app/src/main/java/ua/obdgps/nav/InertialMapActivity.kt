package ua.obdgps.nav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Test map reads ONLY inertial locations written by NavService's DeadReckoner.
 * This Activity never obtains GPS, Fused Location, or phone location.
 */
class InertialMapActivity : Activity() {
    private val pageBackgroundColor=Color.rgb(28,33,40)
    private val surface=Color.rgb(51,59,70)
    private val primaryTextColor=Color.rgb(236,242,249)
    private val secondary=Color.rgb(180,193,209)
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var map: InertialTrackMapView
    private lateinit var info: TextView
    private fun dp(n:Int)=(resources.displayMetrics.density*n+0.5f).toInt()

    private fun button(label:String, action:()->Unit): Button = Button(this).apply {
        text=label
        isAllCaps=false
        textSize=14f
        setTextColor(primaryTextColor)
        setBackgroundColor(surface)
        setOnClickListener { action() }
    }

    private fun mapButton(label:String,action:()->Unit):Button =
        Button(this).apply {
            text=label
            isAllCaps=false
            textSize=21f
            gravity=Gravity.CENTER
            includeFontPadding=false
            setTextColor(Color.WHITE)
            minHeight=0;minimumHeight=0
            minWidth=0;minimumWidth=0
            background=android.graphics.drawable.GradientDrawable().apply {
                cornerRadius=dp(12).toFloat()
                setColor(Color.argb(164,30,39,53))
                setStroke(dp(1),Color.argb(160,210,225,244))
            }
            setPadding(0,0,0,0)
            setOnClickListener{action()}
        }

    private val refresh=object: Runnable {
        override fun run() {
            val obdDr=State.networkDrPoint
            val obdDrTrail=State.networkDrTrail.toList()
            val raw=State.rawGpsTrail.toList()
            map.updateDiagnostic(obdDr,obdDrTrail,raw)
            val lastRaw=raw.lastOrNull()
            val gpsLabel=lastRaw?.let{
                String.format(Locale.US,"%.5f, %.5f",it.latitude,it.longitude)
            }?:"нет точек"
            val obdDrLabel=obdDr?.let{
                String.format(Locale.US,"%.5f, %.5f · %.1f км/ч",
                    it.latitude,it.longitude,it.speedKmh)
            }?:"нет стартовой точки"
            val offset=if(lastRaw!=null && obdDr!=null){
                val from=android.location.Location("gps").apply{
                    latitude=lastRaw.latitude;longitude=lastRaw.longitude
                }
                val to=android.location.Location("sensor").apply{
                    latitude=obdDr.latitude;longitude=obdDr.longitude
                }
                val metres=from.distanceTo(to).toDouble()
                if(metres>=1000.0)
                    String.format(Locale.getDefault(),"%.1f км",metres/1000.0)
                else String.format(Locale.getDefault(),"%.0f м",metres)
            } else "—"
            info.text=State.diagnosticText+
                "\nGPS: $gpsLabel · точек: ${raw.size}"+
                "\nDR без GPS: $obdDrLabel · точек: ${obdDrTrail.size}"+
                "\nРасхождение GPS / DR: $offset"+
                "\n"+State.diagnosticNetworkStatus+
                "\n"+State.gpsSpoofStatus
            handler.postDelayed(this,1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=pageBackgroundColor
        window.navigationBarColor=pageBackgroundColor
        window.decorView.systemUiVisibility=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val column=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor)
            setPadding(dp(12),dp(7),dp(12),dp(8))
            setOnApplyWindowInsetsListener { view,insets ->
                val bars=insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left+dp(12),bars.top+dp(7),
                    bars.right+dp(12),bars.bottom+dp(8))
                insets
            }
        }
        val titleRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text="Тест GPS / DR без GPS"
            textSize=20f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(primaryTextColor)
        },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        titleRow.addView(button("Назад") { finish() },
            LinearLayout.LayoutParams(dp(82),dp(44)))
        column.addView(titleRow)

        info=TextView(this).apply {
            setTextColor(secondary)
            textSize=13f
            setPadding(dp(2),dp(8),dp(2),dp(10))
            text="Ожидание расчётных координат..."
        }
        column.addView(info)
        val buttons=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
        }
        buttons.addView(button("▶ Начать два трека"){
            try {
                startForegroundService(Intent(this,NavService::class.java)
                    .setAction(NavService.START_DIAGNOSTIC))
            }catch(e:Exception){
                info.text="Не удалось запустить тест: "+(e.localizedMessage?:"ошибка")
            }
        },LinearLayout.LayoutParams(0,dp(47),1f))
        buttons.addView(button("■ Остановить"){
            startService(Intent(this,NavService::class.java)
                .setAction(NavService.STOP_DIAGNOSTIC))
        },LinearLayout.LayoutParams(0,dp(47),1f))
        column.addView(buttons)
        val container=FrameLayout(this)
        map=InertialTrackMapView(this)
        container.addView(map,FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        val controls=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(4),dp(4),dp(4),dp(4))
        }
        controls.addView(mapButton("+") { map.changeZoom(1) },
            LinearLayout.LayoutParams(dp(50),dp(50)))
        controls.addView(mapButton("−") { map.changeZoom(-1) },
            LinearLayout.LayoutParams(dp(50),dp(50)).apply { topMargin=dp(5) })
        controls.addView(mapButton("◎") { map.followCar() },
            LinearLayout.LayoutParams(dp(50),dp(50)).apply { topMargin=dp(5) })
        container.addView(controls,FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.RIGHT).apply {setMargins(dp(4),dp(12),dp(8),0)})
        column.addView(container,LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,0,1f))

        val legend=TextView(this).apply {
            text="● Зелёный — GPS    ● Синий — DR + OBD + сеть (БЕЗ GPS)\n"+
                 "● Оранжевый — отклонённый GPS"+
                 "\nВторой трек: гироскоп, OBD через Bluetooth, Wi-Fi/сотовая сеть"+
                 "\nОдна начальная координата и курс · затем GPS запрещён"
            setTextColor(secondary)
            textSize=12f
            gravity=Gravity.CENTER
            setPadding(0,dp(8),0,dp(4))
        }
        column.addView(legend)
        val attribution=TextView(this).apply {
            text="© OpenStreetMap contributors · Нажми для лицензии"
            setTextColor(Color.rgb(145,194,242))
            textSize=11f
            gravity=Gravity.CENTER
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.openstreetmap.org/copyright")))
            }
        }
        column.addView(attribution)
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}

/** Visible OSM raster tiles, seven-day disk caching, and a local-grid fallback. */
internal class InertialTrackMapView(context:Context): View(context) {
    // Initial view is 1.5x closer than version 0.9.2. The same scale
    // multiplier is retained as automatic speed-based zoom changes.
    // 384px per OSM tile in 0.9.2 -> 576px in this version.
    private val tilePixelSize=576.0
    private val tilePaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val gridPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(65,75,87);strokeWidth=1f
    }
    private val routeOutline=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(6,32,44);strokeWidth=11f;style=Paint.Style.STROKE
        strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
    }
    private val navigationPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(255,177,58);strokeWidth=10f
        style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND
        strokeCap=Paint.Cap.ROUND
    }
    private val destinationPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(237,76,84);style=Paint.Style.FILL
    }
    private val startPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(171,117,242);style=Paint.Style.FILL
    }
    private val routePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(22,207,206);strokeWidth=6f;style=Paint.Style.STROKE
        strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
    }
    private val pinHalo=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(10,33,49);style=Paint.Style.FILL
    }
    private val pinFill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(32,152,255);style=Paint.Style.FILL
    }
    private val navigationArrowOutline=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE
        strokeWidth=5.5f
        style=Paint.Style.STROKE
        strokeJoin=Paint.Join.ROUND
    }
    private val navigationArrowSpot=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE
        style=Paint.Style.FILL
    }
    private val cache=object:LruCache<String,Bitmap>(24*1024) {
        override fun sizeOf(key:String,value:Bitmap)=max(1,value.byteCount/1024)
    }
    private val loading=HashSet<String>()
    private val failedUntil=HashMap<String,Long>()
    private val worker=Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable,"Visible-OSM-Tiles").apply{isDaemon=true}
    }
    // App-private persistent storage survives restarts and system cache clearing.
    private val tileDir=File(context.filesDir,"offline-maps/viewed").apply{mkdirs()}
    private var offlineGeneration=-1
    private val handler=Handler(Looper.getMainLooper())
    private var current:State.TrailPoint?=null
    private var markerBearing:Double?=null
    private var history=emptyList<State.TrailPoint>()
    private var diagnosticMode=false
    private var gpsRawHistory=emptyList<State.RawGpsPoint>()
    private var obdNetworkHistory=emptyList<State.TrailPoint>()
    private var follow=true
    private var navigation=false
    private var routeGuidanceActive=false
    private var routePoints:List<RoutingEngine.Point> = emptyList()
    private var destination:RoutingEngine.Point?=null
    private var manualOrigin:RoutingEngine.Point?=null
    private var selectionCallback:((Double,Double)->Unit)?=null
    private var tapDownAt=0L
    private var movedDuringTouch=false
    private var downX=0f
    private var downY=0f
    private var centerLat=50.4501 // Static map start, not user GPS.
    private var centerLon=30.5234
    private var zoom=18
    // Heading-up camera with delayed acceptance of large course jumps.
    private var headingUp=true
    private var cameraHeading=0.0
    private var targetHeading:Double?=null
    private var headingInitialized=false
    private var lastAnimationMs=0L
    private var headingCandidate:Double?=null
    private var headingCandidateFirstMs=0L
    private var lastStableHeadingMs=0L
    // Hysteresis prevents zoom changes at every small fluctuation in speed.
    private var smoothedKmh=Double.NaN
    private var candidateZoom=18
    private var candidateZoomSince=0L
    private var lastAutoZoomMs=0L
    private var manualZoomUntilMs=0L
    private var touchX=0f
    private var touchY=0f
    private var multitouchSeen=false
    private var pinchAccumulator=1.0
    private val scaleDetector=ScaleGestureDetector(context,
        object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector:ScaleGestureDetector):Boolean {
                multitouchSeen=true
                movedDuringTouch=true
                follow=false
                pinchAccumulator=1.0
                return true
            }
            override fun onScale(detector:ScaleGestureDetector):Boolean {
                val factor=detector.scaleFactor.toDouble()
                if(!factor.isFinite() || factor<=0.0)return false
                pinchAccumulator=(pinchAccumulator*factor).coerceIn(0.15,6.0)
                while(pinchAccumulator>=1.23 && zoom<19) {
                    zoomAround(zoom+1,detector.focusX,detector.focusY)
                    pinchAccumulator/=1.23
                }
                while(pinchAccumulator<=1.0/1.23 && zoom>11) {
                    zoomAround(zoom-1,detector.focusX,detector.focusY)
                    pinchAccumulator*=1.23
                }
                return true
            }
        })

    private fun anchorX():Double=width/2.0
    // Position the car below center in navigation, leaving more road ahead.
    private fun anchorY():Double=if(navigation && current!=null)
        height*0.66 else height/2.0

    private fun deltaAngle(to:Double,from:Double):Double =
        ((to-from+540.0)%360.0+360.0)%360.0-180.0

    private fun normalizedCourse(degrees:Double):Double=
        ((degrees%360.0)+360.0)%360.0

    private fun acceptHeading(course:Double?,speedKmh:Double?) {
        if(!headingUp || !follow || course==null || !course.isFinite())return
        // At rest GNSS bearings become unstable; keep the last trusted course.
        if(speedKmh==null || !speedKmh.isFinite() || speedKmh<5.0)return
        val now=SystemClock.elapsedRealtime()
        val heading=normalizedCourse(course)
        if(!headingInitialized) {
            cameraHeading=heading
            targetHeading=heading
            headingInitialized=true
            headingCandidate=null
            lastAnimationMs=now
            return
        }
        val currentTarget=targetHeading?:heading
        val difference=abs(deltaAngle(heading,currentTarget))
        if(difference<=46.0) {
            targetHeading=heading
            headingCandidate=null
            lastStableHeadingMs=now
        } else {
            // Ignore one-off 90/180-degree compass jumps. A true turn is
            // accepted after the new direction persists for ~0.9 seconds.
            val prev=headingCandidate
            if(prev==null || abs(deltaAngle(heading,prev))>28.0) {
                headingCandidate=heading
                headingCandidateFirstMs=now
            } else if(now-headingCandidateFirstMs>=850L) {
                targetHeading=heading
                headingCandidate=null
                lastStableHeadingMs=now
            }
        }
    }

    private fun advanceCamera() {
        if(!headingInitialized || !headingUp || !follow)return
        val target=targetHeading?:return
        val now=SystemClock.elapsedRealtime()
        val dt=(now-lastAnimationMs).coerceIn(1L,120L).toDouble()
        lastAnimationMs=now
        val diff=deltaAngle(target,cameraHeading)
        if(abs(diff)<0.12) {
            cameraHeading=normalizedCourse(target)
            return
        }
        // EMA with a roughly 650ms time constant, capped at 130 degrees/s.
        val part=(1.0-exp(-dt/650.0))*diff
        val limited=part.coerceIn(-130.0*dt/1000.0,130.0*dt/1000.0)
        cameraHeading=normalizedCourse(cameraHeading+limited)
        postInvalidateDelayed(32L)
    }

    private fun autoZoomForSpeed(speedKmh:Double?) {
        if(!follow || speedKmh==null || !speedKmh.isFinite() ||
            speedKmh !in 0.0..250.0)return
        val now=SystemClock.elapsedRealtime()
        if(now<manualZoomUntilMs)return
        smoothedKmh=if(smoothedKmh.isNaN())speedKmh
            else smoothedKmh*.72+speedKmh*.28
        val newZoom=when {
            smoothedKmh<25.0 -> 18
            smoothedKmh<55.0 -> 17
            smoothedKmh<105.0 -> 16
            else -> 15
        }
        if(newZoom!=candidateZoom) {
            candidateZoom=newZoom
            candidateZoomSince=now
        }
        if(newZoom!=zoom && now-candidateZoomSince>=2600L &&
            now-lastAutoZoomMs>=4300L) {
            // Keep the car anchored while the display range changes.
            zoomAround(newZoom,anchorX().toFloat(),anchorY().toFloat())
            lastAutoZoomMs=now
        }
    }

    /** Return map-plane offsets for a touch coordinate on the rotated screen. */
    private fun screenOffsets(x:Double,y:Double):Pair<Double,Double> {
        val dx=x-anchorX()
        val dy=y-anchorY()
        val angle=Math.toRadians(if(headingInitialized && headingUp)
            cameraHeading else 0.0)
        return Pair(cos(angle)*dx-sin(angle)*dy,
            sin(angle)*dx+cos(angle)*dy)
    }

    private fun dragMap(dx:Double,dy:Double) {
        val radians=Math.toRadians(if(headingInitialized&&headingUp)
            cameraHeading else 0.0)
        val deltaX=cos(radians)*dx-sin(radians)*dy
        val deltaY=sin(radians)*dx+cos(radians)*dy
        val world=tilePixelSize*(1 shl zoom)
        val nx=((worldX(centerLon)-deltaX)%world+world)%world
        val ny=(worldY(centerLat)-deltaY).coerceIn(1.0,world-1.0)
        centerLon=nx/world*360.0-180.0
        centerLat=Math.toDegrees(atan(sinh(Math.PI*(1.0-2.0*ny/world))))
        invalidate()
    }

    fun updateDiagnostic(
        sensorPoint:State.TrailPoint?,
        sensorTrail:List<State.TrailPoint>,
        gpsTrail:List<State.RawGpsPoint>
    ){
        diagnosticMode=true
        obdNetworkHistory=sensorTrail
        gpsRawHistory=gpsTrail
        current=sensorPoint
        markerBearing=sensorPoint?.bearing
        if(follow){
            val anchor=sensorPoint?.let{it.latitude to it.longitude}
                ?:gpsTrail.lastOrNull{it.approved}?.let{
                    it.latitude to it.longitude
                }
            if(anchor!=null){
                centerLat=anchor.first
                centerLon=anchor.second
            }
            // Keep the test map centered on the GPS-free OBD/network track.
            // Suspect GPS teleports cannot move the camera.
        }
        invalidate()
    }

    fun updateFromInertial(point:State.TrailPoint?,trail:List<State.TrailPoint>) {
        diagnosticMode=false
        current=point;history=trail
        markerBearing=point?.bearing?.takeIf { it.isFinite() }
        if(follow && point!=null) {
            val speed=point.speedKmh.takeIf {
                it.isFinite() && SystemClock.elapsedRealtime()-point.elapsedMs<5000L
            }
            acceptHeading(markerBearing,speed)
            autoZoomForSpeed(speed)
            centerLat=point.latitude;centerLon=point.longitude
        }
        invalidate()
    }

    fun enableNavigationMode(onPick:(Double,Double)->Unit) {
        navigation=true
        selectionCallback=onPick
    }

    /**
     * "Start navigation" must reattach the camera to the car after the user
     * panned to choose a destination, and immediately face the planned route.
     * If a real vehicle heading becomes available, follow it with smoothing.
     */
    fun beginRouteGuidance(routeCourse:Double?,fallback:RoutingEngine.Point?) {
        routeGuidanceActive=true
        follow=true
        manualZoomUntilMs=0L
        smoothedKmh=Double.NaN
        val fix=current
        if(fix!=null) {
            centerLat=fix.latitude
            centerLon=fix.longitude
        } else if(fallback!=null) {
            centerLat=fallback.lat
            centerLon=fallback.lon
        }
        routeCourse?.takeIf{it.isFinite()}?.let {
            cameraHeading=normalizedCourse(it)
            targetHeading=cameraHeading
            headingInitialized=true
            headingCandidate=null
        }
        lastAnimationMs=SystemClock.elapsedRealtime()
        invalidate()
    }

    fun stopRouteGuidance() {
        routeGuidanceActive=false
        invalidate()
    }

    fun updateFromNavigation(
        fix:State.NavFix?,
        route:List<RoutingEngine.Point>,
        destination:RoutingEngine.Point?,
        origin:RoutingEngine.Point?,
        speedKmh:Double?=null,
        routeCourse:Double?=null,
        guidanceActive:Boolean=false
    ) {
        diagnosticMode=false
        navigation=true
        routeGuidanceActive=guidanceActive
        routePoints=route
        this.destination=destination
        manualOrigin=origin
        val measuredCourse=fix?.bearing?.takeIf{it.isFinite()}
        val guidedCourse=routeCourse?.takeIf{it.isFinite()}
        val courseForCamera=if(guidanceActive) {
            if(speedKmh!=null && speedKmh>=8.0)measuredCourse?:guidedCourse
            else guidedCourse?:measuredCourse
        } else measuredCourse
        markerBearing=measuredCourse?:if(guidanceActive)guidedCourse else null
        current=fix?.let {
            State.TrailPoint(it.lat,it.lon,markerBearing?:0.0,0.0,it.elapsedMs)
        }
        // Show inertial breadcrumbs only when DR is active.
        history=if(State.dr)State.inertialTrail.toList() else emptyList()
        if(follow&&fix!=null) {
            centerLat=fix.lat;centerLon=fix.lon
            if(guidanceActive && courseForCamera==guidedCourse && guidedCourse!=null)
                acceptHeading(guidedCourse,maxOf(8.0,speedKmh?:0.0))
            else acceptHeading(courseForCamera,speedKmh)
            autoZoomForSpeed(speedKmh)
        }
        invalidate()
    }

    fun centerAt(lat:Double,lon:Double) {
        centerLat=lat;centerLon=lon
        follow=false
        invalidate()
    }

    private fun geoAt(x:Float,y:Float):Pair<Double,Double> {
        val world=tilePixelSize*(1 shl zoom)
        val delta=screenOffsets(x.toDouble(),y.toDouble())
        val px=((worldX(centerLon)+delta.first)%world+world)%world
        val py=(worldY(centerLat)+delta.second).coerceIn(1.0,world-1.0)
        val longitude=px/world*360.0-180.0
        val latitude=Math.toDegrees(atan(sinh(Math.PI*(1.0-2.0*py/world))))
        return Pair(latitude,longitude)
    }

    fun followCar() {
        follow=true
        manualZoomUntilMs=0L
        lastAnimationMs=SystemClock.elapsedRealtime()
        current?.let{centerLat=it.latitude;centerLon=it.longitude}
        smoothedKmh=Double.NaN
        invalidate()
    }

    fun changeZoom(amount:Int) {
        // Zoom buttons override automatic scaling for one minute.
        manualZoomUntilMs=SystemClock.elapsedRealtime()+60_000L
        zoomAround((zoom+amount).coerceIn(11,19),
            anchorX().toFloat(),anchorY().toFloat())
    }

    /** Keep the geographic point below the fingers fixed while zooming. */
    private fun zoomAround(targetZoom:Int,focusX:Float,focusY:Float) {
        if(zoom==targetZoom || width<=0 || height<=0)return
        val (anchorLat,anchorLon)=geoAt(focusX,focusY)
        zoom=targetZoom
        val world=tilePixelSize*(1 shl zoom)
        val offset=screenOffsets(focusX.toDouble(),focusY.toDouble())
        val centerWorldX=worldX(anchorLon)-offset.first
        val centerWorldY=worldY(anchorLat)-offset.second
        centerLon=(((centerWorldX%world)+world)%world)/world*360.0-180.0
        val my=centerWorldY.coerceIn(1.0,world-1.0)
        centerLat=Math.toDegrees(atan(sinh(Math.PI*(1.0-2.0*my/world))))
        invalidate()
    }

    private fun worldX(lon:Double)=((lon+180.0)/360.0)*tilePixelSize*(1 shl zoom)
    private fun worldY(lat:Double):Double {
        val a=Math.toRadians(lat.coerceIn(-85.051128,85.051128))
        return (1.0-ln(tan(a)+1.0/cos(a))/Math.PI)/2.0*tilePixelSize*(1 shl zoom)
    }
    private fun point(lat:Double,lon:Double):Pair<Float,Float> {
        val world=tilePixelSize*(1 shl zoom)
        var dx=worldX(lon)-worldX(centerLon)
        if(dx>world/2)dx-=world
        if(dx< -world/2)dx+=world
        return Pair((anchorX()+dx).toFloat(),
            (anchorY()+worldY(lat)-worldY(centerLat)).toFloat())
    }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(34,40,48))
        if(width<=0 || height<=0)return
        advanceCamera()
        val rotation=if(headingInitialized && headingUp)cameraHeading else 0.0

        // Rotate map tiles, road route and track together, not the HUD.
        canvas.save()
        canvas.rotate((-rotation).toFloat(),anchorX().toFloat(),anchorY().toFloat())

        // Cover the inverse-rotated screen bounds so corners never become blank.
        val corners=listOf(
            screenOffsets(0.0,0.0), screenOffsets(width.toDouble(),0.0),
            screenOffsets(0.0,height.toDouble()),
            screenOffsets(width.toDouble(),height.toDouble())
        )
        val minDX=corners.minOf {it.first}
        val maxDX=corners.maxOf {it.first}
        val minDY=corners.minOf {it.second}
        val maxDY=corners.maxOf {it.second}

        // Background grid follows the same heading as the street layer.
        val gridLeft=floor((anchorX()+minDX)/80.0).toInt()-1
        val gridRight=ceil((anchorX()+maxDX)/80.0).toInt()+1
        val gridTop=floor((anchorY()+minDY)/80.0).toInt()-1
        val gridBottom=ceil((anchorY()+maxDY)/80.0).toInt()+1
        for(x in gridLeft..gridRight) {
            val xPos=x*80f
            canvas.drawLine(xPos,(anchorY()+minDY).toFloat(),xPos,
                (anchorY()+maxDY).toFloat(),gridPaint)
        }
        for(y in gridTop..gridBottom) {
            val yPos=y*80f
            canvas.drawLine((anchorX()+minDX).toFloat(),yPos,
                (anchorX()+maxDX).toFloat(),yPos,gridPaint)
        }

        val n=1 shl zoom
        val worldCenterX=worldX(centerLon)
        val worldCenterY=worldY(centerLat)
        val left=floor((worldCenterX+minDX)/tilePixelSize).toInt()
        val right=floor((worldCenterX+maxDX)/tilePixelSize).toInt()
        val top=floor((worldCenterY+minDY)/tilePixelSize).toInt()
        val bottom=floor((worldCenterY+maxDY)/tilePixelSize).toInt()
        for(y in top..bottom) {
            if(y<0 || y>=n)continue
            for(rawX in left..right) {
                val x=((rawX%n)+n)%n
                val sx=(rawX*tilePixelSize-worldCenterX+anchorX()).toFloat()
                val sy=(y*tilePixelSize-worldCenterY+anchorY()).toFloat()
                val bitmap=tileBitmap(zoom,x,y)
                if(bitmap!=null)
                    canvas.drawBitmap(bitmap,null,RectF(sx,sy,sx+tilePixelSize.toFloat(),sy+tilePixelSize.toFloat()),
                        tilePaint)
            }
        }
        if(navigation && routePoints.size>=2) {
            val path=Path()
            var first=true
            var lastX=0f;var lastY=0f
            for(routePoint in routePoints) {
                val (x,y)=point(routePoint.lat,routePoint.lon)
                if(first || abs(x-lastX)>width*.8f || abs(y-lastY)>height*.8f) {
                    path.moveTo(x,y);first=false
                } else path.lineTo(x,y)
                lastX=x;lastY=y
            }
            canvas.drawPath(path,navigationPaint)
        }
        if(navigation) {
            destination?.let {
                val (x,y)=point(it.lat,it.lon)
                canvas.drawCircle(x,y,17f,destinationPaint)
            }
            manualOrigin?.let {
                val (x,y)=point(it.lat,it.lon)
                canvas.drawCircle(x,y,12f,startPaint)
            }
        }
        if(diagnosticMode) {
            drawDiagnosticTraces(canvas)
        } else if(history.size>=2) {
            val line=Path()
            var first=true
            var previousX=0f;var previousY=0f
            for(entry in history) {
                val (x,y)=point(entry.latitude,entry.longitude)
                if(first || abs(x-previousX)>width*.8f || abs(y-previousY)>height*.8f) {
                    line.moveTo(x,y);first=false
                } else line.lineTo(x,y)
                previousX=x;previousY=y
            }
            canvas.drawPath(line,routeOutline)
            canvas.drawPath(line,routePaint)
        }
        current?.let {
            val (x,y)=point(it.latitude,it.longitude)
            canvas.drawCircle(x,y,32f,pinHalo)
            val course=markerBearing
            if(course==null) {
                // A heading that is not known must not be displayed as north.
                canvas.drawCircle(x,y,18f,navigationArrowSpot)
                canvas.drawCircle(x,y,14f,pinFill)
            } else {
                // Large vehicle chevron, similar to familiar navigation arrows.
                // North-up map: rotate clockwise by actual travel heading.
                canvas.save()
                // While following, the vehicle arrow stays upright and the map
                // rotates smoothly beneath it. When panned, use actual course.
                val drawnCourse=if(follow && headingInitialized)
                    rotation else course
                canvas.rotate(drawnCourse.toFloat(),x,y)
                val arrow=Path().apply {
                    moveTo(x,y-34f)
                    lineTo(x+18f,y+23f)
                    lineTo(x,y+13f)
                    lineTo(x-18f,y+23f)
                    close()
                }
                canvas.drawPath(arrow,navigationArrowOutline)
                canvas.drawPath(arrow,pinFill)
                canvas.drawCircle(x,y-4f,4.5f,navigationArrowSpot)
                canvas.restore()
            }
        }
        canvas.restore()
    }

    private val rawGpsLine=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(51,220,119)
        style=Paint.Style.STROKE
        strokeWidth=7f
        strokeCap=Paint.Cap.ROUND
        strokeJoin=Paint.Join.ROUND
    }
    private val obdNetworkLine=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(20,146,255)
        style=Paint.Style.STROKE
        strokeWidth=8f
        strokeCap=Paint.Cap.ROUND
        strokeJoin=Paint.Join.ROUND
    }
    private val rejectedGpsPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(255,172,50)
        style=Paint.Style.FILL
    }

    private fun drawDiagnosticTraces(canvas:Canvas){
        // GPS is never blended with the OBD+network DR track. Suspicious
        // observations are orange, isolated and cannot drag the camera
        // across the world during a spoofing event.
        val gpsPath=Path()
        var gpsStarted=false
        var oldX=0f
        var oldY=0f
        var prev:State.RawGpsPoint?=null
        for(sample in gpsRawHistory){
            val (x,y)=point(sample.latitude,sample.longitude)
            if(!sample.approved){
                if(x in -30f..(width+30f) && y in -30f..(height+30f))
                    canvas.drawCircle(x,y,10f,rejectedGpsPaint)
                gpsStarted=false
                prev=null
                continue
            }
            val discontinuity=prev?.let{
                val dy=(sample.latitude-it.latitude)*111195.0
                val dx=(sample.longitude-it.longitude)*111195.0*
                    cos(Math.toRadians(sample.latitude))
                hypot(dx,dy)>3000.0
            }?:true
            if(!gpsStarted || discontinuity ||
                abs(x-oldX)>width*.8f || abs(y-oldY)>height*.8f)
                gpsPath.moveTo(x,y)
            else gpsPath.lineTo(x,y)
            gpsStarted=true
            oldX=x;oldY=y
            prev=sample
        }
        canvas.drawPath(gpsPath,rawGpsLine)

        val drPath=Path()
        var drStarted=false
        var lastX=0f
        var lastY=0f
        for(sample in obdNetworkHistory){
            val (x,y)=point(sample.latitude,sample.longitude)
            if(!drStarted || abs(x-lastX)>width*.8f ||
                abs(y-lastY)>height*.8f)
                drPath.moveTo(x,y)
            else drPath.lineTo(x,y)
            drStarted=true
            lastX=x;lastY=y
        }
        canvas.drawPath(drPath,obdNetworkLine)
        gpsRawHistory.lastOrNull{it.approved}?.let{
            val (x,y)=point(it.latitude,it.longitude)
            canvas.drawCircle(x,y,11f,rawGpsLine.apply{
                style=Paint.Style.FILL
            })
            rawGpsLine.style=Paint.Style.STROKE
        }
    }

    private fun tileBitmap(z:Int,x:Int,y:Int):Bitmap? {
        val version=OfflineMapStore.generation
        if(version!=offlineGeneration) {
            offlineGeneration=version
            cache.evictAll()
            failedUntil.clear()
        }
        val key=z.toString()+"_"+x+"_"+y
        cache.get(key)?.let{return it}
        val now=SystemClock.elapsedRealtime()
        synchronized(loading) {
            if(key in loading || loading.size>=24 || (failedUntil[key]?:0L)>now)
                return null
            loading.add(key)
        }
        worker.execute {
            try {
                val file=OfflineMapStore.visibleTileFile(context,key)
                // First: selected raster MBTiles archive, for offline area coverage.
                val raw=OfflineMapStore.readRasterTile(context,z,x,y)
                var bitmap=raw?.let{BitmapFactory.decodeByteArray(it,0,it.size)}
                if(bitmap==null && file.exists() && file.length()>0L) {
                    // Raster tiles actually viewed previously, in persistent storage.
                    bitmap=BitmapFactory.decodeFile(file.absolutePath)
                    if(bitmap==null)file.delete()
                }
                if(bitmap==null) {
                    // Never prefetch nearby tiles: request ONLY currently visible
                    // tiles in onDraw. This respects OSM's no-bulk-download rule.
                    downloadTile(z,x,y,file)
                    bitmap=BitmapFactory.decodeFile(file.absolutePath)
                } else if(raw==null && file.exists()) {
                    // Keep ≥ 7-day cache lifetime; refresh older visited tiles
                    // opportunistically while online, never block offline use.
                    val sevenDays=7L*24L*60L*60L*1000L
                    if(System.currentTimeMillis()-file.lastModified()>sevenDays)
                        try { downloadTile(z,x,y,file) } catch (_:Exception) {}
                }
                if(bitmap!=null) {
                    cache.put(key,bitmap)
                    handler.post{invalidate()}
                } else throw IllegalStateException("Map tile unavailable")
            } catch (_:Exception) {
                synchronized(loading){
                    failedUntil[key]=SystemClock.elapsedRealtime()+45_000L
                }
            } finally {
                synchronized(loading){loading.remove(key)}
            }
        }
        return null
    }

    private fun downloadTile(z:Int,x:Int,y:Int,file:File) {
        val url="https://tile.openstreetmap.org/"+z+"/"+x+"/"+y+".png"
        val conn=URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout=6000
        conn.readTimeout=7500
        conn.useCaches=true
        conn.setRequestProperty("User-Agent",
            "OBD-GPS-Navigator/0.9.8 (Android; https://github.com/morozfiles/gps)")
        conn.setRequestProperty("Accept","image/png")
        if(file.exists() && file.lastModified()>0L)
            conn.ifModifiedSince=file.lastModified()
        try {
            if(conn.responseCode==HttpURLConnection.HTTP_NOT_MODIFIED) {
                file.setLastModified(System.currentTimeMillis())
                return
            }
            if(conn.responseCode!=200)
                throw IllegalStateException("Tile HTTP "+conn.responseCode)
            if(!conn.contentType.orEmpty().startsWith("image/"))
                throw IllegalStateException("Not image")
            val tmp=File(tileDir,file.name+".part")
            try {
                conn.inputStream.use{input->
                    tmp.outputStream().use{out->
                        val buffer=ByteArray(8192)
                        var total=0
                        while(true){
                            val bytes=input.read(buffer)
                            if(bytes<0)break
                            total+=bytes
                            if(total>2_000_000)throw IllegalStateException("Tile too big")
                            out.write(buffer,0,bytes)
                        }
                    }
                }
                if(BitmapFactory.decodeFile(tmp.absolutePath)==null)
                    throw IllegalStateException("Corrupt tile")
                if(!tmp.renameTo(file)) {
                    file.writeBytes(tmp.readBytes())
                    tmp.delete()
                }
            } finally {tmp.delete()}
        } finally {conn.disconnect()}
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        // Pass all pointer events to the native scaling detector first.
        scaleDetector.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX=event.x;touchY=event.y
                downX=event.x;downY=event.y
                tapDownAt=SystemClock.elapsedRealtime()
                movedDuringTouch=false
                multitouchSeen=false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multitouchSeen=true
                movedDuringTouch=true
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                multitouchSeen=true
                movedDuringTouch=true
                val removed=event.actionIndex
                val active=if(removed==0)1 else 0
                if(active<event.pointerCount) {
                    touchX=event.getX(active)
                    touchY=event.getY(active)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(event.pointerCount>1 || scaleDetector.isInProgress)
                    return true
                if(multitouchSeen)return true
                val dx=event.x-touchX
                val dy=event.y-touchY
                if(abs(event.x-downX)>16f || abs(event.y-downY)>16f)
                    movedDuringTouch=true
                if(movedDuringTouch && (abs(dx)>2f||abs(dy)>2f)) {
                    val wasFollowing=follow
                    follow=false
                    // screen rotation is frozen while panning; map movement
                    // must be converted back to geographic axes.
                    dragMap(dx.toDouble(),dy.toDouble())
                    if(wasFollowing) manualZoomUntilMs=
                        SystemClock.elapsedRealtime()+60_000L
                }
                touchX=event.x;touchY=event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val isTap=!movedDuringTouch && !multitouchSeen &&
                    SystemClock.elapsedRealtime()-tapDownAt<650L
                if(isTap && navigation) {
                    val (lat,lon)=geoAt(event.x,event.y)
                    selectionCallback?.invoke(lat,lon)
                }
                multitouchSeen=false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                multitouchSeen=false
                return true
            }
        }
        return true
    }
}
