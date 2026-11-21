package ua.obdgps.nav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.speech.tts.TextToSpeech
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * The ONLY mobile navigation map. Google SDK renders tiles, Routes API provides
 * alternative driving routes. A separate route odometer draws the estimated
 * in-app marker; nothing is sent to Android mock location providers.
 */
class GoogleDrMapActivity:Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private val client by lazy { GoogleRouteClient(applicationContext) }
    private val geocodeWorker=Executors.newSingleThreadExecutor()
    private lateinit var mapView:MapView
    private lateinit var searchField:EditText
    private lateinit var searchBar:LinearLayout
    private lateinit var tripRow:LinearLayout
    private lateinit var status:TextView
    private lateinit var direction:TextView
    private lateinit var routeOptions:LinearLayout
    private lateinit var routesRow:HorizontalScrollView
    private lateinit var routeButton:Button
    private lateinit var startButton:Button
    private lateinit var stopButton:Button
    private lateinit var maneuverCard:LinearLayout
    private lateinit var maneuverTitle:TextView
    private lateinit var maneuverDistance:TextView
    private lateinit var maneuverGlyph:TextView
    private lateinit var etaValue:TextView
    private lateinit var remainingValue:TextView
    private lateinit var speedValue:TextView
    private var map:GoogleMap?=null
    private var destination:RoutingEngine.Point?=null
    private var candidates=emptyList<RoutingEngine.Route>()
    private var chosen=0
    private var polylines=ArrayList<Polyline>()
    private var car:Marker?=null
    private var drivenLine:Polyline?=null
    private var drivenIndex=-1
    private var cameraHeading:Double?=null
    private var cameraZoom:Float?=null
    private var speech:TextToSpeech?=null
    private var speechReady=false
    private var voiceEnabled=true
    private lateinit var soundButton:Button
    private var announcedKey=""
    private var offRouteSinceMs=0L
    private var lastRerouteAt=0L
    private var reroutePending=false
    private var latestDisplayed:State.NavFix?=null
    private var latestSpeedKmh:Double?=null
    private var displayHeading:Double?=null
    private var goal:Marker?=null
    private var active=false
    private var fetching=false
    private var followCar=true
    private var searchRequest=0
    private val tracker=RouteOdometer()
    private val blue=Color.rgb(26,115,232)
    private val dark=Color.rgb(32,33,36)
    private val tint=Color.rgb(247,249,252)

    private fun dp(n:Int)=(resources.displayMetrics.density*n+0.5f).toInt()
    private fun rounded(color:Int,radius:Int=22)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius).toFloat()
    }
    private fun button(text:String,background:Int,foreground:Int=Color.WHITE,
                       action:()->Unit)=Button(this).apply {
        this.text=text
        isAllCaps=false
        textSize=15f
        setTextColor(foreground)
        this.background=rounded(background,22)
        minHeight=dp(47)
        setPadding(dp(12),0,dp(12),0)
        elevation=dp(2).toFloat()
        setOnClickListener{action()}
    }
    private fun surface()=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        background=rounded(Color.WHITE,24)
        elevation=dp(6).toFloat()
        setPadding(dp(12),dp(9),dp(12),dp(10))
    }
    private fun showError(message:String) {
        if(::status.isInitialized)status.text=message
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.statusBarColor=tint
        window.navigationBarColor=Color.BLACK
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if(!BuildConfig.GOOGLE_MAPS_CONFIGURED || BuildConfig.GOOGLE_ROUTES_KEY.isBlank()) {
            val message=TextView(this).apply {
                text="Для Google Maps нужны действующие ключи Maps SDK for Android и Routes API."
                textSize=19f
                setPadding(dp(20),dp(60),dp(20),dp(20))
            }
            setContentView(message)
            return
        }

        val root=FrameLayout(this)
        root.setBackgroundColor(tint)
        // Android 15+ edge-to-edge is enforced. The map begins BELOW the
        // status bar (battery/notifications) and ends above gesture navigation.
        root.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        mapView=MapView(this)
        mapView.onCreate(savedInstanceState)
        root.addView(mapView,FrameLayout.LayoutParams(-1,-1))

        // Search sits directly on Google map, styled as a floating Google-like pill.
        searchBar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            background=rounded(Color.WHITE,28)
            elevation=dp(7).toFloat()
            setPadding(dp(9),dp(2),dp(8),dp(2))
        }
        searchField=EditText(this).apply {
            hint="Поиск адреса или места"
            setSingleLine(true)
            textSize=16f
            setTextColor(dark)
            setHintTextColor(Color.GRAY)
            background=null
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8),0,dp(8),0)
            setOnEditorActionListener { _,id,_ ->
                if(id==EditorInfo.IME_ACTION_SEARCH){search();true}else false
            }
        }
        searchBar.addView(searchField,LinearLayout.LayoutParams(0,dp(54),1f))
        val searchIcon=button("⌕",Color.WHITE,blue){search()}
        searchIcon.textSize=25f
        searchBar.addView(searchIcon,LinearLayout.LayoutParams(dp(49),dp(49)))
        maneuverGlyph=TextView(this).apply {
            text="↑";textSize=36f;setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
        }
        maneuverTitle=TextView(this).apply {
            text="Продолжай движение";textSize=17f;setTextColor(Color.WHITE)
            setTypeface(null,Typeface.BOLD);maxLines=2
        }
        maneuverDistance=TextView(this).apply {
            text="";textSize=14f;setTextColor(Color.WHITE)
        }
        maneuverCard=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            background=rounded(Color.rgb(24,113,220),16)
            elevation=dp(5).toFloat()
            setPadding(dp(10),dp(6),dp(12),dp(6))
            visibility=View.GONE
            addView(maneuverGlyph,LinearLayout.LayoutParams(dp(56),dp(61)))
            addView(LinearLayout(this@GoogleDrMapActivity).apply {
                orientation=LinearLayout.VERTICAL
                addView(maneuverTitle)
                addView(maneuverDistance)
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        val top=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(searchBar,LinearLayout.LayoutParams(-1,dp(58)))
            addView(maneuverCard,LinearLayout.LayoutParams(-1,-2).apply {
                topMargin=dp(10)
            })
        }
        val topParams=FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply {
            setMargins(dp(12),dp(9),dp(12),0)
        }
        root.addView(top,topParams)

        val rightControls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val locate=button("◎",Color.WHITE,dark){centerOnCar()}
        locate.textSize=27f
        rightControls.addView(locate,LinearLayout.LayoutParams(dp(54),dp(54)))
        val gear=button("⚙",Color.WHITE,dark) {
            startActivity(Intent(this,MainActivity::class.java))
        }
        gear.textSize=23f
        val gearParams=LinearLayout.LayoutParams(dp(54),dp(54)).apply{topMargin=dp(10)}
        rightControls.addView(gear,gearParams)
        soundButton=button("♫",Color.WHITE,dark) {
            voiceEnabled=!voiceEnabled
            if(!voiceEnabled)speech?.stop()
            soundButton.text=if(voiceEnabled)"♫" else "✕"
            Toast.makeText(this,if(voiceEnabled)"Голос включён"
                else "Голос выключен",Toast.LENGTH_SHORT).show()
        }
        val soundParams=LinearLayout.LayoutParams(dp(54),dp(54)).apply {
            topMargin=dp(10)
        }
        rightControls.addView(soundButton,soundParams)
        val rightParams=FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT or Gravity.TOP).apply {
            setMargins(0,dp(79),dp(12),0)
        }
        root.addView(rightControls,rightParams)

        val bottom=surface()
        status=TextView(this).apply {
            text="Найди место или коснись точки на карте"
            setTextColor(dark)
            textSize=16f
            setTypeface(null,Typeface.BOLD)
            setPadding(dp(5),dp(3),dp(5),dp(4))
        }
        direction=TextView(this).apply {
            text="Google Maps · GPS + OBD + гироскоп + сеть"
            setTextColor(Color.DKGRAY)
            textSize=13f
            setPadding(dp(5),0,dp(5),dp(9))
        }
        bottom.addView(status)
        bottom.addView(direction)
        tripRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            visibility=View.GONE
            tag="tripRow"
            setPadding(0,dp(3),0,dp(8))
        }
        fun tripCell(caption:String):TextView {
            val column=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER
            }
            val label=TextView(this).apply {
                text=caption;textSize=12f;setTextColor(Color.GRAY)
                gravity=Gravity.CENTER
            }
            val value=TextView(this).apply {
                text="—";textSize=20f;setTextColor(dark)
                setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER
            }
            column.addView(value)
            column.addView(label)
            tripRow.addView(column,LinearLayout.LayoutParams(0,-2,1f))
            return value
        }
        etaValue=tripCell("Прибытие")
        remainingValue=tripCell("Осталось")
        speedValue=tripCell("км/ч")
        bottom.addView(tripRow)
        routeOptions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        routesRow=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
            addView(routeOptions)
            visibility=View.GONE
        }
        bottom.addView(routesRow,LinearLayout.LayoutParams(-1,dp(57)))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        routeButton=button("Маршрут",blue){plan()}
        startButton=button("Поехали",blue){startGuidance()}
        stopButton=button("Завершить",Color.rgb(232,234,237),dark){stopGuidance()}
        actions.addView(routeButton,LinearLayout.LayoutParams(0,dp(49),1f))
        val action2=LinearLayout.LayoutParams(0,dp(49),1f).apply{leftMargin=dp(8)}
        actions.addView(startButton,action2)
        actions.addView(stopButton,LinearLayout.LayoutParams(0,dp(49),1f))
        bottom.addView(actions)
        val bottomParams=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply {
            setMargins(dp(12),0,dp(12),dp(12))
        }
        root.addView(bottom,bottomParams)
        setContentView(root)
        updateButtons()

        mapView.getMapAsync { google ->
            map=google
            google.mapType=GoogleMap.MAP_TYPE_NORMAL
            google.uiSettings.isMyLocationButtonEnabled=false
            google.uiSettings.isMapToolbarEnabled=false
            google.uiSettings.isCompassEnabled=true
            google.uiSettings.isZoomControlsEnabled=false
            // Keep Google logo/attributions visible ABOVE the route panel.
            google.setPadding(0,dp(155),0,dp(229))
            google.setOnMapClickListener { selectDestination(it.latitude,it.longitude,"Точка на карте") }
            google.setOnPoiClickListener { poi ->
                selectDestination(poi.latLng.latitude,poi.latLng.longitude,poi.name)
            }
            google.setOnPolylineClickListener { poly ->
                val index=polylines.indexOf(poly)
                if(index>=0 && !active)chooseRoute(index)
            }
            google.setOnCameraMoveStartedListener { reason ->
                if(reason==GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE)
                    followCar=false
            }
            val f=State.navFix
            google.moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(f?.lat?:50.4501,f?.lon?:30.5234),15f))
        }
        speech=TextToSpeech(this) { code ->
            speechReady=code==TextToSpeech.SUCCESS
            if(speechReady)speech?.language=Locale("ru","RU")
        }
        ensurePermissionsAndMonitoring()
    }

    private fun ensurePermissionsAndMonitoring() {
        val required=arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
        if(Build.VERSION.SDK_INT>=33)required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing=required.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty())requestPermissions(missing.toTypedArray(),492)
        else startMonitoring()
    }
    private fun startMonitoring(){
        try {
            startForegroundService(Intent(this,NavService::class.java)
                .setAction(NavService.MONITOR))
        }catch(e:Exception){showError("Мониторинг GPS/OBD: "+(e.localizedMessage?:"ошибка"))}
    }
    override fun onRequestPermissionsResult(requestCode:Int,
                permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==492){
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
                PackageManager.PERMISSION_GRANTED)startMonitoring()
            else showError("Для навигации нужно разрешение на местоположение")
        }
    }

    @Suppress("DEPRECATION")
    private fun search() {
        val term=searchField.text.toString().trim()
        if(term.length<3){showError("Введи название или адрес");return}
        val request=++searchRequest
        status.text="Ищу: "+term
        val keyboard=getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(searchField.windowToken,0)
        searchField.clearFocus()
        // Android's on-device/network geocoder is used; no OSM/Photon map or
        // search results are rendered, and no extra paid Places API is needed.
        val coordinate=Regex("""^(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)$""")
            .matchEntire(term)
        if(coordinate!=null) {
            val lat=coordinate.groupValues[1].toDoubleOrNull()
            val lon=coordinate.groupValues[2].toDoubleOrNull()
            if(lat!=null && lon!=null && lat in -85.0..85.0 && lon in -180.0..180.0){
                selectDestination(lat,lon,term)
                return
            }
        }
        geocodeWorker.execute {
            val result=runCatching {
                require(Geocoder.isPresent()){"Системный поиск адресов недоступен"}
                Geocoder(this,Locale("uk","UA"))
                    .getFromLocationName(term,7).orEmpty().filter {
                        it.hasLatitude() && it.hasLongitude()
                    }
            }
            handler.post {
                if(isFinishing || isDestroyed || request!=searchRequest)return@post
                result.fold(onSuccess={addresses->
                    if(addresses.isEmpty()){showError("Ничего не найдено. Укажи точку на карте.");return@fold}
                    val labels=addresses.map {
                        it.getAddressLine(0)?:it.featureName?:"Найденный адрес"
                    }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Выбери место")
                        .setItems(labels){_,n->
                            selectDestination(addresses[n].latitude,
                                addresses[n].longitude,labels[n])
                        }.setNegativeButton("Отмена",null).show()
                    status.text="Выбери подходящий адрес"
                },onFailure={e->showError("Поиск адреса: "+(e.localizedMessage?:"ошибка"))})
            }
        }
    }
    private fun selectDestination(lat:Double,lon:Double,label:String) {
        if(active)stopGuidance()
        destination=RoutingEngine.Point(lat,lon)
        goal?.remove()
        goal=map?.addMarker(MarkerOptions().position(LatLng(lat,lon)).title(label))
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat,lon),15f))
        candidates=emptyList()
        clearRoutes()
        status.text=label.take(90)
        direction.text="Нажми «Маршрут», чтобы увидеть варианты Google"
        updateButtons()
    }
    private fun plan(){
        val to=destination?:run{showError("Сначала найди адрес или выбери точку");return}
        val now=SystemClock.elapsedRealtime()
        val start=listOfNotNull(State.navFix,State.networkFix).firstOrNull{
            now-it.elapsedMs in 0L..15000L &&
                it.lat.isFinite() && it.lon.isFinite()
        }?:run{
            showError("Нет свежей начальной позиции. Включи GPS/сеть и разрешения.")
            return
        }
        if(fetching)return
        fetching=true
        routeButton.isEnabled=false
        status.text="Google рассчитывает варианты маршрута..."
        client.requestAlternatives(RoutingEngine.Point(start.lat,start.lon),to){result->
            fetching=false
            if(isFinishing || isDestroyed)return@requestAlternatives
            routeButton.isEnabled=true
            result.fold(onSuccess={routes->
                if(routes.isEmpty()){showError("Google не вернул маршрутов");return@fold}
                candidates=routes
                chosen=0
                drawRoutes()
                showRouteOptions()
                fitRoute(routes[0])
                updateButtons()
                status.text="Выбери маршрут · "+routes.size+" вариант(ов)"
                direction.text="Выбери линию на карте или вариант ниже, затем «Поехали»"
            },onFailure={e->
                showError("Маршрут Google: "+(e.localizedMessage?:"нет ответа"))
            })
        }
    }
    private fun clearRoutes(){
        polylines.forEach{it.remove()}
        polylines.clear()
        routesRow.visibility=View.GONE
        routeOptions.removeAllViews()
    }
    private fun drawRoutes(){
        drivenLine?.remove()
        drivenLine=null
        drivenIndex=-1
        polylines.forEach{it.remove()}
        polylines.clear()
        val google=map?:return
        for((idx,r) in candidates.withIndex()){
            val primary=idx==chosen
            val line=google.addPolyline(PolylineOptions()
                .addAll(r.points.map{LatLng(it.lat,it.lon)})
                .color(if(primary)blue else Color.rgb(151,157,164))
                .width(if(primary)dp(8).toFloat() else dp(5).toFloat())
                .zIndex(if(primary)2f else 1f)
                .clickable(true))
            polylines.add(line)
        }
    }
    private fun showRouteOptions(){
        routeOptions.removeAllViews()
        routesRow.visibility=View.VISIBLE
        for((idx,r) in candidates.withIndex()){
            val selected=idx==chosen
            val minutes=(r.seconds/60).coerceAtLeast(1)
            val title=(if(idx==0)"Основной" else "Вариант "+(idx+1))+
                " · "+String.format(Locale.getDefault(),"%.1f км",r.lengthKm)+
                " · "+minutes+" мин"
            val chip=button(title,if(selected)blue else tint,
                if(selected)Color.WHITE else dark){chooseRoute(idx)}
            val params=LinearLayout.LayoutParams(-2,dp(45)).apply{
                rightMargin=dp(7)
            }
            routeOptions.addView(chip,params)
        }
    }
    private fun chooseRoute(index:Int){
        if(active || index !in candidates.indices)return
        chosen=index
        drawRoutes()
        showRouteOptions()
        fitRoute(candidates[index])
        status.text="Выбран маршрут "+(index+1)+" из "+candidates.size
        direction.text="Нажми «Поехали» для начала движения"
        updateButtons()
    }
    private fun fitRoute(route:RoutingEngine.Route){
        if(route.points.size<2)return
        val builder=LatLngBounds.Builder()
        for(p in route.points)builder.include(LatLng(p.lat,p.lon))
        runCatching{map?.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(),dp(65)))}
    }
    private fun updateButtons(){
        if(!::routeButton.isInitialized)return
        routeButton.visibility=if(active || destination==null)View.GONE else View.VISIBLE
        startButton.visibility=if(!active && candidates.isNotEmpty())View.VISIBLE else View.GONE
        stopButton.visibility=if(active)View.VISIBLE else View.GONE
        routesRow.visibility=if(!active && candidates.isNotEmpty())View.VISIBLE else View.GONE
        searchBar.visibility=if(active)View.GONE else View.VISIBLE
        maneuverCard.visibility=if(active)View.VISIBLE else View.GONE
        tripRow.visibility=if(active)View.VISIBLE else View.GONE
    }

    private fun startGuidance(){
        val route=candidates.getOrNull(chosen)?:run{showError("Сначала выбери маршрут");return}
        active=true
        tracker.bind(route,State.navFix)
        followCar=true
        cameraHeading=null
        cameraZoom=null
        displayHeading=null
        announcedKey=""
        offRouteSinceMs=0L
        speech?.stop()
        State.activeRouting=true
        State.gpsFilterRoutePoints=route.points
        val start=route.points.first()
        val bearing=routeBearing(route.points[0],route.points[1])
        State.routeDrSeed=State.NavFix(start.lat,start.lon,bearing,90f,
            "Старт маршрута",SystemClock.elapsedRealtime())
        if(!State.dr && !freshTrustedGps()){
            try{startForegroundService(Intent(this,NavService::class.java)
                .setAction(NavService.START_ROUTE_DR))}
            catch(e:Exception){showError("Датчики: "+(e.localizedMessage?:"ошибка"))}
        }
        status.text="Ведение по Google Maps включено"
        direction.text="GPS + сеть; при потере GPS — расчёт по OBD"
        updateButtons()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    private fun stopGuidance(){
        active=false
        tracker.reset()
        State.activeRouting=false
        State.gpsFilterRoutePoints=emptyList()
        State.routeDrSeed=null
        speech?.stop()
        announcedKey=""
        cameraHeading=null
        cameraZoom=null
        displayHeading=null
        offRouteSinceMs=0L
        drivenLine?.remove()
        drivenLine=null
        drivenIndex=-1
        status.text="Навигация завершена"
        direction.text="Можно выбрать другой маршрут или новую цель"
        updateButtons()
        map?.let{candidates.getOrNull(chosen)?.let{r->fitRoute(r)}}
    }
    private fun formatDistance(meters:Double):String =
        if(meters>=1000.0)String.format(Locale.getDefault(),"%.1f км",meters/1000.0)
        else meters.roundToInt().coerceAtLeast(0).toString()+" м"

    private fun arrowIcon() : com.google.android.gms.maps.model.BitmapDescriptor {
        val size=dp(65)
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val cx=size/2f;val cy=size/2f
        paint.color=Color.argb(76,0,0,0)
        canvas.drawCircle(cx,cy+dp(2),dp(23).toFloat(),paint)
        paint.color=Color.WHITE
        canvas.drawCircle(cx,cy,dp(22).toFloat(),paint)
        paint.color=blue
        canvas.drawCircle(cx,cy,dp(19).toFloat(),paint)
        val arrow=Path().apply{
            moveTo(cx,cy-dp(16))
            lineTo(cx+dp(12),cy+dp(13))
            lineTo(cx,cy+dp(7))
            lineTo(cx-dp(12),cy+dp(13))
            close()
        }
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=dp(3).toFloat()
        paint.strokeJoin=Paint.Join.ROUND
        paint.color=Color.WHITE
        canvas.drawPath(arrow,paint)
        paint.style=Paint.Style.FILL
        paint.color=Color.WHITE
        canvas.drawPath(arrow,paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun speak(text:String){
        if(voiceEnabled && speechReady && text.isNotBlank())
            speech?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"google-route-voice")
    }

    private fun showTurnAndTrip(route:RoutingEngine.Route,
            progress:RoutingEngine.Progress,speedKmh:Double?,gpsTrusted:Boolean,
            now:Long){
        tripRow.visibility=View.VISIBLE
        val left=progress.remainingMeters.coerceAtLeast(0.0)
        remainingValue.text=formatDistance(left)
        val minutes=(progress.secondsRemaining/60).coerceAtLeast(0)
        etaValue.text=SimpleDateFormat("HH:mm",Locale.getDefault())
            .format(Date(System.currentTimeMillis()+progress.secondsRemaining.toLong()*1000L))
        speedValue.text=speedKmh?.roundToInt()?.toString()?:"—"
        status.text=(if(gpsTrusted)"GPS" else "Расчётная позиция OBD/DR")+
            " · "+minutes+" мин до прибытия"
        val next=progress.nextTurn
        maneuverCard.visibility=View.VISIBLE
        if(progress.arrived){
            maneuverGlyph.text="✓"
            maneuverTitle.text="Вы прибыли в пункт назначения"
            maneuverDistance.text=""
            direction.text="Маршрут завершён"
            if(announcedKey!="arrival"){
                announcedKey="arrival"
                speak("Вы прибыли в пункт назначения")
            }
            return
        }
        if(next!=null){
            maneuverGlyph.text=when(next.type){
                1->"↰"
                2->"↱"
                3->"↑"
                4->"↶"
                5->"⟳"
                else->"↗"
            }
            maneuverTitle.text=next.instruction.ifBlank{"Следующий манёвр"}
            maneuverDistance.text="Через "+formatDistance(progress.nextTurnMeters)
            direction.text="Двигайся по выбранному маршруту Google"
            val band=when{
                progress.nextTurnMeters<=35.0->"now"
                progress.nextTurnMeters<=150.0->"150"
                progress.nextTurnMeters<=420.0->"420"
                else->""
            }
            val key=next.shapeIndex.toString()+":"+band
            if(band.isNotEmpty() && key!=announcedKey){
                announcedKey=key
                val speechText=when(band){
                    "now"->next.instruction
                    else->"Через "+progress.nextTurnMeters.roundToInt()+
                        " метров: "+next.instruction
                }
                speak(speechText)
            }
        }else{
            maneuverGlyph.text="↑"
            maneuverTitle.text="Следуй по маршруту"
            maneuverDistance.text=formatDistance(left)+" до цели"
            direction.text="Двигайся по выбранному маршруту Google"
        }
    }

    private fun drawCompletedRoute(route:RoutingEngine.Route,
            index:Int,fix:State.NavFix) {
        val idx=index.coerceIn(0,route.points.lastIndex)
        if(idx==drivenIndex)return
        drivenIndex=idx
        drivenLine?.remove()
        if(idx<1)return
        val trail=route.points.take(idx+1).map{LatLng(it.lat,it.lon)}
        drivenLine=map?.addPolyline(PolylineOptions().addAll(trail)
            .color(Color.rgb(177,181,188)).width(dp(7).toFloat()).zIndex(3f))
    }

    private fun moveGuidanceCamera(fix:State.NavFix,route:RoutingEngine.Route,
                    progress:RoutingEngine.Progress,speedKmh:Double?) {
        val m=map?:return
        val course=fix.bearing?.takeIf{it.isFinite()}
            ?:route.points.getOrNull(progress.nearestShapeIndex+1)?.let{
                routeBearing(route.points[progress.nearestShapeIndex],it)
            }
        // Camera bearing is smoothed through 0/360 degrees. Keep the last
        // heading at a standstill to avoid compass jitter rotating the map.
        cameraHeading=NavigationCameraMath.smoothBearing(cameraHeading,
            if((speedKmh?:0.0)>=3.0)course else null,27.0)
            ?:NavigationCameraMath.normalizeBearing(course?:0.0)
        val targetZoom=NavigationCameraMath.zoom(speedKmh,progress.nextTurnMeters)
        cameraZoom=if(cameraZoom==null)targetZoom else
            (cameraZoom!!+(targetZoom-cameraZoom!!)*0.34f)
        val center=NavigationCameraMath.routeLookAhead(route.points,
            progress.nearestShapeIndex,RoutingEngine.Point(fix.lat,fix.lon),
            NavigationCameraMath.lookAheadMeters(speedKmh))
        val camera=CameraPosition.Builder().target(LatLng(center.lat,center.lon))
            .bearing(cameraHeading!!.toFloat()).zoom(cameraZoom!!)
            .tilt(NavigationCameraMath.tilt(speedKmh)).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(camera),850,null)
    }

    /** No rerouting from spoofed, network-only or DR-estimated positions. */
    private fun checkOffRoute(route:RoutingEngine.Route,raw:State.NavFix?,
                    trustedGps:Boolean,now:Long) {
        if(!active || !trustedGps || raw==null || State.dr){
            offRouteSinceMs=0L
            return
        }
        val lateral=route.progress(RoutingEngine.Point(raw.lat,raw.lon)).lateralMeters
        if(lateral<125.0){offRouteSinceMs=0L;return}
        if(offRouteSinceMs==0L)offRouteSinceMs=now
        if(now-offRouteSinceMs<15_000L || now-lastRerouteAt<120_000L ||
            reroutePending)return
        val to=destination?:return
        lastRerouteAt=now
        reroutePending=true
        direction.text="Отклонение от маршрута. Перестраиваю по GPS..."
        val origin=RoutingEngine.Point(raw.lat,raw.lon)
        client.requestAlternatives(origin,to){result->
            reroutePending=false
            if(isFinishing || isDestroyed || !active)return@requestAlternatives
            result.fold(onSuccess={options->
                if(options.isNotEmpty()){
                    candidates=options;chosen=0
                    tracker.bind(options[0],State.navFix)
                    State.gpsFilterRoutePoints=options[0].points
                    drawRoutes()
                    offRouteSinceMs=0L
                    announcedKey=""
                    speak("Маршрут перестроен")
                }
            },onFailure={e->
                direction.text="Перестроение не удалось: "+
                    (e.localizedMessage?:"сеть")
            })
        }
    }

    private fun freshTrustedGps():Boolean{
        val f=State.navFix?:return false
        return f.source=="GPS" && f.accuracy in 0f..30f &&
            SystemClock.elapsedRealtime()-f.elapsedMs in 0L..5000L &&
            (State.gpsSpeedKmh?:0.0)<=180.0 &&
            !AirAlerts.autoNavigationAlertActive()
    }
    private fun centerOnCar(){
        followCar=true
        val fix=latestDisplayed?:State.navFix
        val route=candidates.getOrNull(chosen)
        if(active && fix!=null && route!=null) {
            val progress=route.progress(RoutingEngine.Point(fix.lat,fix.lon))
            moveGuidanceCamera(fix,route,progress,latestSpeedKmh)
            return
        }
        val p=fix?.let{LatLng(it.lat,it.lon)}
        if(p!=null)map?.animateCamera(CameraUpdateFactory.newLatLngZoom(p,16f))
        else showError("Координаты пока не получены")
    }
    private val refresh=object:Runnable{
        override fun run(){
            val now=SystemClock.elapsedRealtime()
            val raw=State.navFix?.takeIf{now-it.elapsedMs in 0L..12000L}
            val route=candidates.getOrNull(chosen)
            val obd=State.obdSpeedKmh()?.takeIf{
                State.speedUpdatedElapsedMs>0L &&
                now-State.speedUpdatedElapsedMs in 0L..3500L &&
                it.isFinite() && it in 0.0..180.0
            }
            val gpsSpeed=State.gpsSpeedKmh?.takeIf {
                State.gpsSpeedUpdatedElapsedMs>0L &&
                now-State.gpsSpeedUpdatedElapsedMs in 0L..6000L &&
                it.isFinite() && it in 0.0..180.0
            }
            latestSpeedKmh=obd?:gpsSpeed
            var visible=raw
            var estimated=false
            if(active && route!=null){
                val projected=tracker.update(now,obd,raw,State.networkFix,
                    State.yawDegPerSec)
                val gnssTrusted=raw!=null && raw.source=="GPS" &&
                    !State.dr && !AirAlerts.autoNavigationAlertActive() &&
                    raw.accuracy in 0f..25f &&
                    (gpsSpeed?:0.0)<=180.0
                val gpsAgrees=gnssTrusted && raw!=null && projected!=null &&
                    RoutingEngine.meters(RoutingEngine.Point(raw.lat,raw.lon),
                        RoutingEngine.Point(projected.lat,projected.lon))<45.0
                visible=if(gpsAgrees)raw else projected?:raw
                estimated=!gpsAgrees
                if(visible!=null){
                    val progress=route.progress(RoutingEngine.Point(visible.lat,visible.lon))
                    showTurnAndTrip(route,progress,latestSpeedKmh,gpsAgrees,now)
                    drawCompletedRoute(route,progress.nearestShapeIndex,visible)
                    if(followCar)moveGuidanceCamera(visible,route,progress,latestSpeedKmh)
                }else{
                    status.text="Положение неизвестно: GPS и OBD недоступны"
                    direction.text="Проверь GPS, сеть и Bluetooth-адаптер"
                    maneuverCard.visibility=View.GONE
                }
                // Accepted GPS evidence can justify rerouting. Never trigger
                // this from DR, Wi-Fi alone or a spoofed GPS jump.
                checkOffRoute(route,raw,gnssTrusted,now)
            }
            latestDisplayed=visible
            if(visible!=null){
                val p=LatLng(visible.lat,visible.lon)
                val heading=visible.bearing?.takeIf{it.isFinite()}
                    ?:route?.points?.takeIf{it.size>1}?.let{
                        val idx=route.progress(RoutingEngine.Point(visible.lat,visible.lon))
                            .nearestShapeIndex.coerceIn(0,it.size-2)
                        routeBearing(it[idx],it[idx+1])
                    }
                displayHeading=NavigationCameraMath.smoothBearing(displayHeading,
                    if((latestSpeedKmh?:0.0)>=2.0)heading else null,40.0)
                    ?:NavigationCameraMath.normalizeBearing(heading?:0.0)
                if(car==null){
                    car=map?.addMarker(MarkerOptions().position(p)
                        .icon(arrowIcon()).anchor(0.5f,0.5f).flat(true)
                        .rotation(displayHeading!!.toFloat()).zIndex(12f)
                        .title("Автомобиль"))
                }else{
                    car?.position=p
                    car?.rotation=displayHeading!!.toFloat()
                }
                car?.title=if(estimated)"Расчётная позиция · GPS ненадёжен"
                    else "Автомобиль · GPS/сеть"
            }
            handler.postDelayed(this,1000L)
        }
    }
    override fun onResume(){
        super.onResume()
        if(::mapView.isInitialized){
            mapView.onResume()
            handler.removeCallbacks(refresh);handler.post(refresh)
        }
    }
    override fun onPause(){
        handler.removeCallbacks(refresh)
        if(::mapView.isInitialized)mapView.onPause()
        super.onPause()
    }
    override fun onStart(){super.onStart();if(::mapView.isInitialized)mapView.onStart()}
    override fun onStop(){if(::mapView.isInitialized)mapView.onStop();super.onStop()}
    override fun onLowMemory(){super.onLowMemory();if(::mapView.isInitialized)mapView.onLowMemory()}
    override fun onSaveInstanceState(state:Bundle){
        super.onSaveInstanceState(state)
        if(::mapView.isInitialized)mapView.onSaveInstanceState(state)
    }
    override fun onDestroy(){
        handler.removeCallbacks(refresh)
        searchRequest++
        geocodeWorker.shutdownNow()
        if(active)stopGuidance()
        speech?.stop()
        speech?.shutdown()
        speech=null
        if(::mapView.isInitialized)mapView.onDestroy()
        super.onDestroy()
    }
    private fun routeBearing(a:RoutingEngine.Point,b:RoutingEngine.Point):Double =
        (Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),
            b.lat-a.lat))+360.0)%360.0
}

/** Pure display/route odometer. Never feeds its estimate into GPS authenticity checks. */
internal class RouteOdometer {
    private var route:RoutingEngine.Route?=null
    private var lengths=DoubleArray(0)
    private var distance=0.0
    private var lastTime=0L
    private var lastFixTime=0L
    private var yaw=0.0
    fun reset(){route=null;lengths=DoubleArray(0);distance=0.0;lastTime=0L;lastFixTime=0L;yaw=0.0}
    fun bind(r:RoutingEngine.Route,initial:State.NavFix?){
        reset();route=r
        lengths=DoubleArray(r.points.size)
        for(i in 1 until r.points.size)lengths[i]=lengths[i-1]+
            RoutingEngine.meters(r.points[i-1],r.points[i])
        distance=initial?.let{project(it.lat,it.lon)?.first}?:0.0
        lastTime=SystemClock.elapsedRealtime()
    }
    fun update(now:Long,obd:Double?,fix:State.NavFix?,
               network:State.NavFix?,yawDegreesPerSecond:Double):State.NavFix? {
        val r=route?:return null
        if(lengths.size<2)return null
        val dt=((now-lastTime)/1000.0).coerceIn(0.0,2.0)
        lastTime=now
        if(obd!=null)distance=(distance+obd/3.6*dt).coerceAtMost(lengths.last())
        if(obd!=null && obd>5 && yawDegreesPerSecond.isFinite())
            yaw=(yaw+yawDegreesPerSecond*dt).coerceIn(-140.0,140.0)
        else yaw*=0.7
        // Turn only to a nearby maneuver, with a strict bounded adjustment.
        if(abs(yaw)>30 && obd!=null) {
            val step=r.maneuvers.firstOrNull{
                val idx=it.shapeIndex.coerceIn(1,r.points.lastIndex-1)
                val before=bearing(r.points[idx-1],r.points[idx])
                val after=bearing(r.points[idx],r.points[idx+1])
                val delta=((after-before+540.0)%360.0)-180.0
                abs(delta)>25 && sign(delta)==sign(yaw) &&
                    lengths[idx] in (distance-20.0)..(distance+45.0)
            }
            if(step!=null){
                distance=max(distance,min(distance+12.0,lengths[step.shapeIndex]))
                yaw=0.0
            }
        }
        for(candidate in listOfNotNull(fix,network)){
            if(candidate.elapsedMs<=lastFixTime || candidate.accuracy>150f)continue
            if(candidate.source.contains("Ручн"))continue
            val matched=project(candidate.lat,candidate.lon)?:continue
            val along=matched.first
            val lateral=matched.second
            if(lateral>max(70.0,candidate.accuracy*1.8))continue
            if(abs(along-distance)>max(90.0,candidate.accuracy*2.5))continue
            val confidence=if(candidate.source=="GPS" && candidate.accuracy<30f)0.12 else 0.04
            distance=(distance+(along-distance).coerceIn(-20.0,20.0)*confidence)
                .coerceIn(0.0,lengths.last())
            lastFixTime=candidate.elapsedMs
        }
        val d=distance.coerceIn(0.0,lengths.last())
        var i=0
        while(i<lengths.lastIndex-1 && lengths[i+1]<d)i++
        val segment=(lengths[i+1]-lengths[i]).coerceAtLeast(0.001)
        val fraction=((d-lengths[i])/segment).coerceIn(0.0,1.0)
        val a=r.points[i];val b=r.points[i+1]
        return State.NavFix(a.lat+(b.lat-a.lat)*fraction,
            a.lon+(b.lon-a.lon)*fraction,bearing(a,b),100f,
            "OBD · расчёт по маршруту",now)
    }
    private fun project(lat:Double,lon:Double):Pair<Double,Double>? {
        val r=route?:return null
        var min=Double.POSITIVE_INFINITY;var along=0.0
        for(i in 0 until r.points.lastIndex){
            // Avoid snapping across a later loop of the same route.
            if(lengths[i+1]<distance-300.0 || lengths[i]>distance+300.0)continue
            val a=r.points[i];val b=r.points[i+1]
            val scale=111195.0;val cosLat=cos(Math.toRadians(lat))
            val ax=(a.lon-lon)*cosLat*scale;val ay=(a.lat-lat)*scale
            val bx=(b.lon-lon)*cosLat*scale;val by=(b.lat-lat)*scale
            val vx=bx-ax;val vy=by-ay;val len=vx*vx+vy*vy
            val t=if(len<0.1)0.0 else (-(ax*vx+ay*vy)/len).coerceIn(0.0,1.0)
            val lateral=hypot(ax+vx*t,ay+vy*t)
            if(lateral<min){min=lateral;along=lengths[i]+(lengths[i+1]-lengths[i])*t}
        }
        return if(min.isFinite())along to min else null
    }
    private fun bearing(a:RoutingEngine.Point,b:RoutingEngine.Point):Double =
        (Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),
            b.lat-a.lat))+360.0)%360.0
}
