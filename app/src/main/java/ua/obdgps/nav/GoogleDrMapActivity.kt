package ua.obdgps.nav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.speech.tts.TextToSpeech
import android.speech.RecognizerIntent
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
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.MapStyleOptions
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
    private val session by lazy { PhoneRouteSession(applicationContext) }
    private val fytCapabilities by lazy { FytHeadUnitProfile.read(this) }
    private val widescreen:Boolean get() =
        BuildConfig.FYT7870_HEAD_UNIT &&
            resources.configuration.orientation==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** Keeps search/route card clear of FYT's right-side speed controls
     * across the various 1024x600, 1280x720 and high-density panels. */
    private fun fytPanelWidth():Int=dp(
        (resources.configuration.screenWidthDp-120).coerceIn(300,560))
    private val searchPrefs by lazy { SearchPlacesPrefs(applicationContext) }
    private val startupProbe by lazy { StartupPositionProbe(this) { onApproximateStartupFix(it) } }
    private var restoredSnapshot:PhoneRouteSession.Snapshot?=null
    private var savedRouteRefreshAttempted=false
    private val geocodeWorker=Executors.newSingleThreadExecutor()
    private lateinit var mapView:MapView
    private lateinit var searchField:EditText
    private lateinit var searchBar:LinearLayout
    private var searchPopup:PopupWindow?=null
    private val voiceSearchRequestCode=8106
    private lateinit var tripRow:LinearLayout
    private lateinit var bottomPanel:LinearLayout
    private lateinit var regularActions:LinearLayout
    private var fytGuidanceRow:LinearLayout?=null
    private var fytNoticeBanner:LinearLayout?=null
    private var fytNoticeStatus:TextView?=null
    private var fytNoticeDirection:TextView?=null
    private var fytNoticeHardware:TextView?=null
    private lateinit var status:TextView
    private lateinit var direction:TextView
    private lateinit var hardwareStatus:TextView
    private lateinit var routeOptions:LinearLayout
    private lateinit var routesRow:HorizontalScrollView
    private lateinit var routeButton:Button
    private lateinit var startButton:Button
    private lateinit var stopButton:Button
    private var oneShotDiagnosticButton:Button?=null
    private var awaitingDiagnosticReturn=false
    private lateinit var maneuverCard:LinearLayout
    private lateinit var maneuverTitle:TextView
    private lateinit var maneuverDistance:TextView
    private lateinit var maneuverGlyph:TextView
    private lateinit var etaValue:TextView
    private lateinit var remainingValue:TextView
    private lateinit var speedValue:TextView
    private lateinit var speedBadgeBackground:GradientDrawable
    private lateinit var speedNumber:TextView
    private lateinit var speedSourceLabel:TextView
    private var displayedSpeedSource="НЕТ ДАННЫХ"
    private var map:GoogleMap?=null
    private var destination:RoutingEngine.Point?=null
    private var candidates=emptyList<RoutingEngine.Route>()
    private var chosen=0
    private var polylines=ArrayList<Polyline>()
    private var car:Marker?=null
    private var accuracyCircle:Circle?=null
    private var initialPositionCentered=false
    private var initialPositionAccuracy=Float.POSITIVE_INFINITY
    private var drivenLine:Polyline?=null
    private var drivenIndex=-1
    private var cameraHeading:Double?=null
    private var cameraZoom:Float?=null
    private var speech:TextToSpeech?=null
    private var speechReady=false
    private var voiceEnabled=false
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
    private val tint=Color.rgb(18,23,32)
    private val glass=Color.argb(211,23,30,42)
    private val glassButton=Color.argb(189,37,47,65)
    private val lightText=Color.rgb(239,244,252)
    private val mutedText=Color.rgb(167,182,202)

    private fun dp(n:Int)=(resources.displayMetrics.density*n+0.5f).toInt()
    private fun rounded(color:Int,radius:Int=22)=GradientDrawable().apply {
        setColor(color)
        cornerRadius=dp(radius).toFloat()
        setStroke(dp(1),Color.argb(65,197,211,230))
    }
    private fun button(text:String,background:Int,foreground:Int=lightText,
                       action:()->Unit)=Button(this).apply {
        this.text=text
        isAllCaps=false
        textSize=15f
        setTextColor(foreground)
        this.background=rounded(Color.argb(if(background==blue)215 else 198,
            Color.red(background),Color.green(background),Color.blue(background)),22)
        minHeight=dp(47)
        setPadding(dp(12),0,dp(12),0)
        elevation=dp(2).toFloat()
        setOnClickListener{action()}
    }
    private fun surface()=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        background=rounded(glass,24)
        elevation=dp(6).toFloat()
        setPadding(dp(12),dp(9),dp(12),dp(10))
    }
    private fun startOneShotFytDiagnostic(){
        if(!BuildConfig.FYT7870_HEAD_UNIT)return
        if(!FytCrashDiagnostics.begin(this)){
            showFytDiagnosticLog()
            return
        }
        awaitingDiagnosticReturn=true
        oneShotDiagnosticButton?.text="Лог"
        FytCrashDiagnostics.mark(this,"Google Maps: запускаю экран настроек FYT")
        try {
            startActivity(Intent(this,MainActivity::class.java))
        }catch(error:Exception){
            awaitingDiagnosticReturn=false
            FytCrashDiagnostics.recordLaunchFailure(this,error)
            showFytDiagnosticLog()
        }
    }

    private fun showFytDiagnosticLog(){
        val content=FytCrashDiagnostics.report(this)
        AlertDialog.Builder(this)
            .setTitle("FYT 7870 · лог диагностики")
            .setMessage(content)
            .setNeutralButton("Копировать"){_,_->
                val clipboard=getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("FYT 7870 ошибка",content))
                Toast.makeText(this,"Лог скопирован. Вставь его в чат.",
                    Toast.LENGTH_LONG).show()
            }
            .setPositiveButton("Поделиться"){_,_->
                val send=Intent(Intent.ACTION_SEND).apply{
                    type="text/plain"
                    putExtra(Intent.EXTRA_SUBJECT,"FYT 7870 · ошибка настроек")
                    putExtra(Intent.EXTRA_TEXT,content)
                }
                runCatching {startActivity(Intent.createChooser(send,"Отправить лог"))}
                    .onFailure {showError("Не удалось открыть отправку лога")}
            }
            .setNegativeButton("Закрыть",null)
            .show()
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
        // White battery, clock and notification icons over dark status bar.
        window.decorView.systemUiVisibility=0
        if(BuildConfig.FYT7870_HEAD_UNIT && !fytCapabilities.googleServices) {
            // Google Maps SDK depends on GMS; do not display an unexplained
            // blank map or pretend the location engine is running.
            val page=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                setBackgroundColor(tint)
                setPadding(dp(36),dp(30),dp(36),dp(16))
            }
            page.addView(TextView(this).apply {
                text="FYT 7870 · нужны Google Play Services"
                textSize=25f
                setTextColor(lightText)
                setTypeface(null,Typeface.BOLD)
            })
            page.addView(TextView(this).apply {
                text="Google Maps SDK не запустится без работающих сервисов Google. "+
                    "Проверь прошивку FYT, наличие Google Play Services и подключение к Интернету. "+
                    "Установка этой APK не устанавливает сервисы Google автоматически."
                textSize=16f
                setTextColor(mutedText)
                setPadding(0,dp(17),0,dp(17))
            })
            page.addView(button("Диагностика OBD и датчиков",blue){
                startActivity(Intent(this,MainActivity::class.java))
            })
            setContentView(page)
            return
        }
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
            background=rounded(glass,28)
            elevation=dp(7).toFloat()
            setPadding(dp(9),dp(2),dp(8),dp(2))
        }
        searchField=EditText(this).apply {
            hint="Поиск адреса или места"
            setSingleLine(true)
            textSize=16f
            setTextColor(lightText)
            setHintTextColor(mutedText)
            background=null
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8),0,dp(8),0)
            setOnClickListener { showSearchDropdown() }
            setOnEditorActionListener { _,id,_ ->
                if(id==EditorInfo.IME_ACTION_SEARCH){search();true}else false
            }
        }
        searchBar.addView(searchField,LinearLayout.LayoutParams(0,dp(54),1f))
        val savedPlacesIcon=button("⌄",glassButton,lightText){showSearchDropdown()}
        savedPlacesIcon.textSize=22f
        savedPlacesIcon.contentDescription="Дом и последние поиски"
        searchBar.addView(savedPlacesIcon,LinearLayout.LayoutParams(dp(39),dp(46)))
        val microphone=button("🎙",glassButton,lightText){startVoiceSearch()}
        microphone.textSize=22f
        microphone.contentDescription="Голосовой поиск адреса"
        searchBar.addView(microphone,LinearLayout.LayoutParams(dp(42),dp(46)))
        val searchIcon=button("⌕",glassButton,lightText){search()}
        searchIcon.textSize=25f
        searchIcon.contentDescription="Найти адрес"
        searchBar.addView(searchIcon,LinearLayout.LayoutParams(dp(42),dp(46)))
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
            background=rounded(Color.argb(224,25,94,179),16)
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
        val topParams=FrameLayout.LayoutParams(
            if(widescreen)fytPanelWidth() else -1,-2,Gravity.TOP).apply {
            setMargins(dp(if(widescreen)18 else 12),dp(9),dp(12),0)
        }
        root.addView(top,topParams)

        val rightControls=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.RIGHT
        }
        val locate=button("◎",glassButton,lightText){centerOnCar()}
        locate.textSize=27f
        rightControls.addView(locate,LinearLayout.LayoutParams(dp(54),dp(54)))
        val gear=button("⚙",glassButton,lightText) {
            startActivity(Intent(this,MainActivity::class.java))
        }
        gear.textSize=23f
        val gearParams=LinearLayout.LayoutParams(dp(54),dp(54)).apply{topMargin=dp(10)}
        rightControls.addView(gear,gearParams)
        if(BuildConfig.FYT7870_HEAD_UNIT){
            val diagnostic=button(
                if(FytCrashDiagnostics.hasRun(this))"Лог" else "Диагн.",
                glassButton,lightText
            ){
                if(FytCrashDiagnostics.hasRun(this))showFytDiagnosticLog()
                else startOneShotFytDiagnostic()
            }
            diagnostic.textSize=11f
            diagnostic.minHeight=dp(32)
            diagnostic.setPadding(dp(2),0,dp(2),0)
            diagnostic.contentDescription=
                "Разовая диагностика падения при открытии второго экрана"
            rightControls.addView(diagnostic,
                LinearLayout.LayoutParams(dp(60),dp(34)).apply{
                    topMargin=dp(6)
                })
            oneShotDiagnosticButton=diagnostic
        }
        soundButton=button("✕",glassButton,lightText) {
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
        // This chip remains visible before and during guidance. It shows
        // actual GPS speed only when GNSS is accepted; otherwise fresh OBD.
        speedNumber=TextView(this).apply {
            text="—";textSize=31f
            setTypeface(null,Typeface.BOLD)
            setTextColor(lightText)
            gravity=Gravity.CENTER
            includeFontPadding=false
        }
        speedSourceLabel=TextView(this).apply {
            text="НЕТ ДАННЫХ";textSize=10f
            setTypeface(null,Typeface.BOLD)
            setTextColor(mutedText)
            gravity=Gravity.CENTER
            includeFontPadding=false
        }
        val speedUnit=TextView(this).apply {
            text="км/ч";textSize=11f;setTextColor(mutedText)
            gravity=Gravity.CENTER
            includeFontPadding=false
        }
        speedBadgeBackground=rounded(glass,10)
        val speedBadge=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            background=speedBadgeBackground
            elevation=dp(4).toFloat()
            setPadding(dp(6),dp(8),dp(6),dp(8))
            isClickable=true
            isFocusable=true
            contentDescription="Скорость движения и источник данных"
            setOnClickListener {
                val gps=State.gpsSpeedKmh
                val obd=State.obdSpeedKmh()
                val description="Сейчас: "+displayedSpeedSource+
                    "\nGPS: "+(gps?.let { String.format(Locale.getDefault(),"%.0f км/ч",it) }
                        ?:"нет данных")+
                    "\nOBD: "+(obd?.let { String.format(Locale.getDefault(),"%.0f км/ч",it) }
                        ?:"нет данных")+
                    "\nДля выбора используются только свежие данные. " +
                    "При потере или недостоверности GPS приоритет у OBD."
                AlertDialog.Builder(this@GoogleDrMapActivity)
                    .setTitle("Источник скорости")
                    .setMessage(description)
                    .setPositiveButton("Понятно",null).show()
            }
            addView(speedNumber)
            addView(speedUnit)
            addView(speedSourceLabel)
        }
        val speedParams=LinearLayout.LayoutParams(dp(77),dp(66)).apply {
            topMargin=dp(11)
        }
        rightControls.addView(speedBadge,speedParams)
        val rightParams=FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT or Gravity.TOP).apply {
            setMargins(0,dp(if(widescreen)93 else 145),dp(14),0)
        }
        root.addView(rightControls,rightParams)

        val bottom=surface()
        bottomPanel=bottom
        status=TextView(this).apply {
            text="Найди место или коснись точки на карте"
            setTextColor(lightText)
            textSize=16f
            setTypeface(null,Typeface.BOLD)
            setPadding(dp(5),dp(3),dp(5),dp(4))
        }
        direction=TextView(this).apply {
            text="Google Maps · GPS + OBD + гироскоп + сеть"
            setTextColor(mutedText)
            textSize=13f
            setPadding(dp(5),0,dp(5),dp(9))
        }
        bottom.addView(status)
        bottom.addView(direction)
        hardwareStatus=TextView(this).apply{
            text=if(BuildConfig.FYT7870_HEAD_UNIT)
                fytCapabilities.summary else ""
            textSize=11f
            setTextColor(mutedText)
            setPadding(dp(5),0,dp(5),dp(5))
            visibility=if(BuildConfig.FYT7870_HEAD_UNIT)View.VISIBLE else View.GONE
            maxLines=2
        }
        bottom.addView(hardwareStatus)
        tripRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            visibility=View.GONE
            tag="tripRow"
            setPadding(0,if(widescreen)0 else dp(3),
                0,if(widescreen)0 else dp(8))
        }
        fun tripCell(caption:String):TextView {
            val column=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER
            }
            val label=TextView(this).apply {
                text=caption;textSize=12f;setTextColor(mutedText)
                gravity=Gravity.CENTER
            }
            val value=TextView(this).apply {
                text="—";textSize=20f;setTextColor(lightText)
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
        if(!widescreen)bottom.addView(tripRow)
        routeOptions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        routesRow=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
            addView(routeOptions)
            visibility=View.GONE
        }
        bottom.addView(routesRow,LinearLayout.LayoutParams(-1,dp(57)))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        regularActions=actions
        routeButton=button("Маршрут",blue){plan()}
        startButton=button("Поехали",blue){startGuidance()}
        stopButton=button("Завершить",glassButton,lightText){stopGuidance()}
        actions.addView(routeButton,LinearLayout.LayoutParams(0,dp(49),1f))
        val action2=LinearLayout.LayoutParams(0,dp(49),1f).apply{leftMargin=dp(8)}
        actions.addView(startButton,action2)
        if(widescreen){
            // One compact bottom HUD: [Finish] [ETA] [Remaining] [Speed].
            // The turn instructions remain in the floating top card.
            fytGuidanceRow=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                visibility=View.GONE
                tag="fytGuidanceRow"
                addView(stopButton,LinearLayout.LayoutParams(dp(142),dp(50)).apply{
                    rightMargin=dp(14)
                })
                addView(tripRow,LinearLayout.LayoutParams(0,dp(53),1f))
            }
            bottom.addView(fytGuidanceRow,LinearLayout.LayoutParams(-1,dp(53)))
        }else {
            actions.addView(stopButton,LinearLayout.LayoutParams(0,dp(49),1f))
        }
        bottom.addView(actions)
        val bottomParams=FrameLayout.LayoutParams(
            if(widescreen)fytPanelWidth() else -1,-2,Gravity.BOTTOM or Gravity.LEFT).apply {
            setMargins(dp(if(widescreen)18 else 12),0,dp(12),dp(12))
        }
        root.addView(bottom,bottomParams)
        if(widescreen) {
            // Restore the three useful route/status notices ABOVE the compact
            // one-row Finish / ETA / Remaining / Speed bar.
            val notice=surface().apply {
                setPadding(dp(14),dp(7),dp(14),dp(7))
                visibility=View.GONE
            }
            fytNoticeStatus=TextView(this).apply {
                textSize=15f
                setTypeface(null,Typeface.BOLD)
                setTextColor(lightText)
                maxLines=2
            }
            fytNoticeDirection=TextView(this).apply {
                textSize=13f
                setTextColor(mutedText)
                maxLines=2
            }
            fytNoticeHardware=TextView(this).apply {
                textSize=11f
                setTextColor(mutedText)
                maxLines=2
            }
            notice.addView(fytNoticeStatus)
            notice.addView(fytNoticeDirection)
            notice.addView(fytNoticeHardware)
            // The bottom navigation bar is 53dp + margins. A separate
            // translucent banner sits directly above without making it tall.
            root.addView(notice,FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,-2,Gravity.BOTTOM).apply {
                setMargins(dp(12),0,dp(12),dp(79))
            })
            fytNoticeBanner=notice
        }
        setContentView(root)
        restoreRouteSession()
        updateButtons()

        mapView.getMapAsync { google ->
            map=google
            google.mapType=GoogleMap.MAP_TYPE_NORMAL
            // Local JSON style, so dark map works without a cloud Map ID.
            val styled=runCatching {
                google.setMapStyle(MapStyleOptions.loadRawResourceStyle(
                    this,R.raw.google_night_map))
            }.getOrDefault(false)
            if(!styled)State.message="Тёмная карта: не удалось применить стиль"
            google.uiSettings.isMyLocationButtonEnabled=false
            google.uiSettings.isMapToolbarEnabled=false
            google.uiSettings.isCompassEnabled=true
            google.uiSettings.isZoomControlsEnabled=false
            // Keep Google logo/attributions visible ABOVE the route panel.
            google.setPadding(0,dp(if(widescreen)86 else 155),0,dp(if(widescreen)176 else 229))
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
            val snapshot=restoredSnapshot
            if(snapshot!=null){
                val d=snapshot.route.destination
                goal=google.addMarker(MarkerOptions().position(LatLng(d.lat,d.lon))
                    .title("Цель сохранённого маршрута"))
                drawRoutes()
                showRouteOptions()
                updateButtons()
                val cached=snapshot.pose
                if(cached!=null) {
                    car=google.addMarker(MarkerOptions()
                        .position(LatLng(cached.lat,cached.lon))
                        .icon(arrowIcon()).anchor(0.5f,0.5f).flat(true)
                        .alpha(0.88f)
                        .rotation((snapshot.bearing?:0.0).toFloat())
                        .title("Последняя сохранённая позиция — может быть устаревшей"))
                }
                if(snapshot.active && cached!=null){
                    val fix=State.NavFix(cached.lat,cached.lon,snapshot.bearing,
                        150f,"Сохранённая позиция",0L)
                    val prog=snapshot.route.progress(cached)
                    moveGuidanceCamera(fix,snapshot.route,prog,null)
                }else orientRoutePreview(snapshot.route)
            }else {
                val f=bestPositionForMap(SystemClock.elapsedRealtime())
                if(f!=null){
                    initialPositionCentered=true
                    initialPositionAccuracy=f.accuracy
                    google.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(f.lat,f.lon),roughZoom(f.accuracy)))
                    updateAccuracyCircle(f)
                }else{
                    // A neutral country-level view, not a fabricated fix
                    // at the center of Kyiv.
                    google.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(49.0,31.5),6f))
                    status.text="Определяю положение: GPS, Wi-Fi, сотовая сеть..."
                }
            }
        }
        speech=TextToSpeech(this) { code ->
            speechReady=code==TextToSpeech.SUCCESS
            if(speechReady)speech?.language=Locale("ru","RU")
        }
        ensurePermissionsAndMonitoring()
    }

    private fun hasLocationPermission():Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
            PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==
            PackageManager.PERMISSION_GRANTED

    /** A weak network/cell estimate can locate the map but not drive navigation. */
    private fun bestPositionForMap(now:Long):State.NavFix? {
        val accepted=State.navFix?.takeIf {
            now-it.elapsedMs in 0L..12000L &&
                !it.source.contains("Ручн") &&
                !GpsSpoofGuard.isLima(it.lat,it.lon)
        }
        val rough=State.approximateFix?.takeIf {
            ApproximateFixStore.usable(it,now)
        }
        return accepted?:rough
    }

    private fun roughZoom(metres:Float):Float=when {
        metres<=30f -> 17f
        metres<=120f -> 15.6f
        metres<=450f -> 14f
        metres<=1500f -> 12.6f
        metres<=5000f -> 10.7f
        metres<=15000f -> 9.3f
        metres<=50000f -> 7.7f
        else -> 6.4f
    }

    private fun maybeCenterStartupPosition(fix:State.NavFix) {
        val g=map?:return
        if(active || !followCar || destination!=null || fetching ||
            !ApproximateFixStore.usable(fix))return
        val newAccuracy=fix.accuracy
        if(initialPositionCentered &&
            newAccuracy>=initialPositionAccuracy*0.65f)return
        initialPositionCentered=true
        initialPositionAccuracy=newAccuracy
        g.animateCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(fix.lat,fix.lon),roughZoom(fix.accuracy)),700,null)
    }

    private fun updateAccuracyCircle(fix:State.NavFix?) {
        val g=map?:return
        if(fix==null || !ApproximateFixStore.usable(fix)){
            accuracyCircle?.isVisible=false
            return
        }
        val p=LatLng(fix.lat,fix.lon)
        val drDrift=if(State.dr && fix.source.contains("OBD"))
            State.driftMeters.coerceAtLeast(0.0) else 0.0
        // In DR mode also visualize the growing estimated inertial drift;
        // network/GPS radius is still Android's reported uncertainty.
        val radius=max(fix.accuracy.toDouble(),drDrift).coerceIn(5.0,100_000.0)
        val fill=Color.argb(20,98,181,255)
        val border=Color.argb(66,145,205,255)
        val old=accuracyCircle
        if(old==null)accuracyCircle=g.addCircle(CircleOptions()
            .center(p).radius(radius).fillColor(fill)
            .strokeColor(border).strokeWidth(dp(1).toFloat())
            .clickable(false).zIndex(0.2f))
        else {
            old.center=p
            old.radius=radius
            old.isVisible=true
        }
    }

    private fun onApproximateStartupFix(fix:State.NavFix) {
        if(!::status.isInitialized || active || isFinishing || isDestroyed)return
        val now=SystemClock.elapsedRealtime()
        val current=bestPositionForMap(now)?:fix
        if(current.source.startsWith("Примерно")) {
            status.text="Положение примерно · ±"+
                formatDistance(current.accuracy.toDouble())
            direction.text="По Wi-Fi/сотовой сети или системному кэшу; " +
                "круг показывает заявленную Android погрешность"
        }
        updateAccuracyCircle(current)
        maybeCenterStartupPosition(current)
    }

    private fun ensurePermissionsAndMonitoring() {
        // Request BOTH permissions: Android 12+ may grant approximate
        // location even when precise access is declined.
        val required=arrayListOf<String>()
        if(!hasLocationPermission()){
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
            required.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        for(permission in arrayOf(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN)) {
            if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)
                required.add(permission)
        }
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=
                PackageManager.PERMISSION_GRANTED)
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing=required
        if(missing.isNotEmpty())requestPermissions(missing.toTypedArray(),492)
        else {
            startupProbe.start()
            startMonitoring()
        }
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
            if(hasLocationPermission()){
                startupProbe.start()
                startMonitoring()
            }else showError("Разреши хотя бы приблизительную геолокацию для поиска положения")
        }
    }

    /** Floating menu stays inside the Google Maps search bar, not a new map. */
    private fun showSearchDropdown() {
        if(active || !::searchBar.isInitialized || isFinishing || isDestroyed)return
        searchPopup?.dismiss()
        if(searchBar.width<=0)return
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=rounded(glass,18)
            elevation=dp(10).toFloat()
            setPadding(dp(8),dp(7),dp(8),dp(7))
        }
        fun heading(title:String) {
            panel.addView(TextView(this).apply {
                text=title
                textSize=12f
                setTextColor(mutedText)
                setPadding(dp(13),dp(9),dp(6),dp(5))
            })
        }
        fun entry(title:String,subtitle:String?=null,action:()->Unit) {
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                background=rounded(glassButton,12)
                setPadding(dp(13),dp(8),dp(10),dp(8))
                isClickable=true
                isFocusable=true
                setOnClickListener {
                    searchPopup?.dismiss()
                    searchPopup=null
                    action()
                }
            }
            item.addView(TextView(this).apply {
                text=title
                textSize=15f
                maxLines=2
                ellipsize=android.text.TextUtils.TruncateAt.END
                setTextColor(lightText)
            })
            if(!subtitle.isNullOrBlank())item.addView(TextView(this).apply {
                text=subtitle
                textSize=12f
                maxLines=2
                ellipsize=android.text.TextUtils.TruncateAt.END
                setTextColor(mutedText)
            })
            panel.addView(item,LinearLayout.LayoutParams(-1,-2).apply {
                bottomMargin=dp(5)
            })
        }
        val saved=searchPrefs.homeAddress
        heading("ИЗБРАННОЕ")
        if(saved.isNotBlank()){
            entry("⌂  Дом",saved){
                searchField.setText(saved)
                searchField.setSelection(saved.length)
                search()
            }
            entry("✎  Изменить адрес дома"){editHomeAddress()}
        }else{
            entry("⌂  Дом","Добавить постоянный адрес дома"){
                editHomeAddress()
            }
        }
        heading("ПОСЛЕДНИЕ ПОИСКИ")
        val recent=searchPrefs.recent()
        if(recent.isEmpty()) {
            panel.addView(TextView(this).apply {
                text="Пока нет сохранённых поисков"
                textSize=13f
                setTextColor(mutedText)
                setPadding(dp(13),dp(8),dp(10),dp(12))
            })
        }else for(query in recent){
            entry("◷  "+query){
                searchField.setText(query)
                searchField.setSelection(query.length)
                search()
            }
        }
        val popup=PopupWindow(panel,searchBar.width,
            WindowManager.LayoutParams.WRAP_CONTENT,true).apply {
            isOutsideTouchable=true
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation=dp(10).toFloat()
            setBackgroundDrawable(rounded(glass,18))
        }
        searchPopup=popup
        popup.showAsDropDown(searchBar,0,dp(6))
    }

    /** The user owns the Home template; editing never uses GPS implicitly. */
    private fun editHomeAddress(){
        val editor=EditText(this).apply {
            hint="Город, улица, номер дома"
            setSingleLine(true)
            setText(searchPrefs.homeAddress)
            setTextColor(lightText)
            setHintTextColor(mutedText)
            setPadding(dp(16),dp(12),dp(16),dp(12))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("Дом · постоянный адрес")
            .setMessage("Укажи адрес, который будет всегда доступен в поиске.")
            .setView(editor)
            .setPositiveButton("Сохранить"){_,_->
                val address=editor.text.toString().trim()
                if(address.length<4) {
                    showError("Адрес слишком короткий — введи улицу и номер дома")
                }else{
                    searchPrefs.homeAddress=address
                    Toast.makeText(this,"Адрес «Дом» сохранён",Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Удалить"){_,_->
                searchPrefs.homeAddress=""
                Toast.makeText(this,"Адрес «Дом» очищен",Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена",null)
            .show()
    }

    /** Uses the Android installed speech recognition UI, not a microphone
     * recorder in this app; the recognised address goes into normal search.
     */
    @Suppress("DEPRECATION")
    private fun startVoiceSearch(){
        if(active)return
        searchPopup?.dismiss()
        val recognition=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
            putExtra(RecognizerIntent.EXTRA_PROMPT,
                "Назови адрес или место назначения")
        }
        try{
            startActivityForResult(recognition,voiceSearchRequestCode)
        }catch(_:Exception){
            showError("Голосовой ввод недоступен. Установи системный сервис распознавания речи.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=voiceSearchRequestCode || resultCode!=RESULT_OK)return
        val words=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull{it.isNotBlank()}?.trim()
        if(words.isNullOrBlank()){
            showError("Адрес не распознан — попробуй ещё раз")
            return
        }
        searchField.setText(words)
        searchField.setSelection(words.length)
        search()
    }

    @Suppress("DEPRECATION")
    private fun search() {
        searchPopup?.dismiss()
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
                searchPrefs.remember(term)
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
                            searchPrefs.remember(term)
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
        session.clear()
        State.currentGoogleRoute=null
        restoredSnapshot=null
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
                it.lat.isFinite() && it.lon.isFinite() &&
                !it.source.contains("Ручн")
        }?:State.approximateFix?.takeIf {
            ApproximateFixStore.usable(it,now)
        }?:run{
            showError("Определяю точку старта по GPS, Wi-Fi и сотовой сети...")
            startupProbe.start()
            return
        }
        if(start.source.startsWith("Примерно") || start.accuracy>250f){
            AlertDialog.Builder(this).setTitle("Старт приблизительный")
                .setMessage("Местоположение получено с погрешностью примерно ±"+
                    formatDistance(start.accuracy.toDouble())+
                    ". Начальная дорога может быть неверной. "+
                    "Построить маршрут от приблизительного центра?")
                .setNegativeButton("Подождать GPS",null)
                .setPositiveButton("Построить"){_,_->requestRouteFrom(start,to)}
                .show()
            return
        }
        requestRouteFrom(start,to)
    }

    private fun requestRouteFrom(start:State.NavFix,to:RoutingEngine.Point){
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
                orientRoutePreview(routes[0])
                updateButtons()
                status.text="Выбери маршрут · "+routes.size+" вариант(ов)"
                saveRouteSession(false)
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
            val chip=button(title,if(selected)blue else glassButton,
                lightText){chooseRoute(idx)}
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
        orientRoutePreview(candidates[index])
        status.text="Выбран маршрут "+(index+1)+" из "+candidates.size
        direction.text="Нажми «Поехали» для начала движения"
        updateButtons()
        saveRouteSession(false)
    }
    private fun orientRoutePreview(route:RoutingEngine.Route) {
        val google=map?:return
        if(route.points.size<2)return
        val current=State.navFix?.takeIf {
            SystemClock.elapsedRealtime()-it.elapsedMs in 0L..15000L
        }
        val candidate=current?.let{
            route.progress(RoutingEngine.Point(it.lat,it.lon)).nearestShapeIndex
        }?:0
        val idx=candidate.coerceIn(0,route.points.size-2)
        val from=route.points[idx]
        val to=route.points[idx+1]
        val bearing=routeBearing(from,to)
        val target=current?.let { LatLng(it.lat,it.lon) }
            ?:LatLng(from.lat,from.lon)
        val camera=CameraPosition.Builder().target(target)
            .bearing(bearing.toFloat()).zoom(15.7f).tilt(28f).build()
        google.animateCamera(CameraUpdateFactory.newCameraPosition(camera),750,null)
    }

    private fun fitRoute(route:RoutingEngine.Route){
        if(route.points.size<2)return
        val builder=LatLngBounds.Builder()
        for(p in route.points)builder.include(LatLng(p.lat,p.lon))
        runCatching{map?.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(),dp(65)))}
    }
    private fun updateFytNotices() {
        if(!widescreen || !active)return
        fytNoticeStatus?.text=status.text
        fytNoticeDirection?.text=direction.text
        fytNoticeHardware?.text=hardwareStatus.text
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
        if(widescreen){
            // Widen only the live-guidance HUD. Route preview stays narrow,
            // leaving the map and destination selection unobstructed.
            status.visibility=if(active)View.GONE else View.VISIBLE
            direction.visibility=if(active)View.GONE else View.VISIBLE
            hardwareStatus.visibility=if(active)View.GONE else View.VISIBLE
            fytNoticeBanner?.visibility=if(active)View.VISIBLE else View.GONE
            fytGuidanceRow?.visibility=if(active)View.VISIBLE else View.GONE
            updateFytNotices()
            regularActions.visibility=if(active)View.GONE else View.VISIBLE
            bottomPanel.setPadding(dp(14),dp(if(active)6 else 9),
                dp(14),dp(if(active)6 else 10))
            val params=bottomPanel.layoutParams as? FrameLayout.LayoutParams
            if(params!=null){
                params.width=if(active)FrameLayout.LayoutParams.MATCH_PARENT
                    else fytPanelWidth()
                params.setMargins(dp(if(active)12 else 18),0,
                    dp(if(active)12 else 12),dp(if(active)7 else 12))
                bottomPanel.layoutParams=params
            }
        }
    }

    private fun saveRouteSession(inProgress:Boolean=active) {
        val chosenRoute=candidates.getOrNull(chosen)?:return
        State.currentGoogleRoute=chosenRoute
        session.save(chosenRoute,inProgress,tracker.progressMeters,
            latestDisplayed?:State.navFix)
    }

    private fun restoreRouteSession() {
        val snapshot=session.load()?:return
        restoredSnapshot=snapshot
        val current=State.currentGoogleRoute
        val persisted=snapshot.route
        val restoredRoute=if(current!=null &&
            current.points.size==persisted.points.size &&
            RoutingEngine.meters(current.destination,persisted.destination)<2.0 &&
            RoutingEngine.meters(current.points[current.points.size/2],
                persisted.points[persisted.points.size/2])<2.0)current
            else persisted
        candidates=listOf(restoredRoute)
        chosen=0
        destination=restoredRoute.destination
        val last=snapshot.pose?.let {
            State.NavFix(it.lat,it.lon,snapshot.bearing,120f,
                "Последняя сохранённая позиция",0L)
        }
        latestDisplayed=last
        tracker.bind(restoredRoute,last,snapshot.progressMeters)
        active=snapshot.active
        State.activeRouting=active
        State.currentGoogleRoute=restoredRoute
        State.gpsFilterRoutePoints=if(active)restoredRoute.points else emptyList()
        val first=restoredRoute.points.first()
        val restoredPoint=last?.let{RoutingEngine.Point(it.lat,it.lon)}?:first
        val segment=restoredRoute.progress(restoredPoint).nearestShapeIndex
            .coerceIn(0,restoredRoute.points.size-2)
        val bearing=routeBearing(restoredRoute.points[segment],
            restoredRoute.points[segment+1])
        State.routeDrSeed=if(active) {
            val anchor=last?:State.NavFix(first.lat,first.lon,bearing,120f,
                "Старт сохранённого маршрута",SystemClock.elapsedRealtime())
            anchor.copy(elapsedMs=SystemClock.elapsedRealtime())
        }else null
        status.text=if(active)"Маршрут восстановлен · GPS/OBD подключаются"
            else "Сохранённый маршрут Google"
        direction.text=if(active)"После возвращения в приложение путь мог измениться. "+
            "Позиция проверяется заново."
            else "Нажми «Поехали» для продолжения"
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
        saveRouteSession(true)
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    private fun stopGuidance(){
        saveRouteSession(false)
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

    /** Just a translucent direction arrow: no circular badge or halo. */
    private fun arrowIcon():com.google.android.gms.maps.model.BitmapDescriptor {
        val size=dp(48)
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.argb(205,97,183,251)
            style=Paint.Style.FILL
        }
        val cx=size/2f
        val cy=size/2f
        val arrow=Path().apply {
            moveTo(cx,cy-dp(19))
            lineTo(cx+dp(14),cy+dp(17))
            lineTo(cx,cy+dp(9))
            lineTo(cx-dp(14),cy+dp(17))
            close()
        }
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
        val receiver=if(gpsTrusted && BuildConfig.FYT7870_HEAD_UNIT)
            (if(State.fytGpsActiveSource.contains("GM906BT"))"GM906BT"
                else "Внутренний GPS")
            else if(gpsTrusted)"GPS" else "Расчётная позиция OBD/DR"
        status.text=receiver+" · "+minutes+" мин до прибытия"
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
        // Avoid false rerouting on a low-quality but still accepted fix.
        // A confirmed wrong turn should be visible after multiple samples.
        if(raw.accuracy>25f){offRouteSinceMs=0L;return}
        val lateral=route.progress(RoutingEngine.Point(raw.lat,raw.lon)).lateralMeters
        if(lateral<max(65.0,raw.accuracy*2.0+25.0)){
            offRouteSinceMs=0L
            return
        }
        if(offRouteSinceMs==0L)offRouteSinceMs=now
        if(!reroutePending)
            direction.text="Возможный съезд с маршрута — проверяю GPS"
        if(now-offRouteSinceMs<8_000L || now-lastRerouteAt<120_000L ||
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
                    saveRouteSession(true)
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

    /**
     * Display-only snapping to a nearby Google route segment. A genuine
     * off-route GPS position remains unsnapped and can trigger a reroute.
     * Never feeds this position into Android GPS or spoofing checks.
     */
    private fun snapAcceptedGps(fix:State.NavFix,
                                route:RoutingEngine.Route):State.NavFix {
        val progress=route.progress(RoutingEngine.Point(fix.lat,fix.lon))
        if(progress.lateralMeters>35.0)return fix
        val idx=progress.nearestShapeIndex.coerceIn(0,route.points.lastIndex-1)
        val a=route.points[idx]
        val b=route.points[idx+1]
        val metersLat=111195.0
        val metersLon=metersLat*cos(Math.toRadians(fix.lat))
        val ax=(a.lon-fix.lon)*metersLon
        val ay=(a.lat-fix.lat)*metersLat
        val bx=(b.lon-fix.lon)*metersLon
        val by=(b.lat-fix.lat)*metersLat
        val dx=bx-ax
        val dy=by-ay
        val length=dx*dx+dy*dy
        val ratio=if(length<0.001)0.0
            else (-ax*dx-ay*dy).div(length).coerceIn(0.0,1.0)
        val nearestLat=a.lat+(b.lat-a.lat)*ratio
        val nearestLon=a.lon+(b.lon-a.lon)*ratio
        val strength=(0.82-fix.accuracy/150.0).coerceIn(0.35,0.72)
        return fix.copy(
            lat=fix.lat+(nearestLat-fix.lat)*strength,
            lon=fix.lon+(nearestLon-fix.lon)*strength,
            bearing=if((latestSpeedKmh?:0.0)>6.0)routeBearing(a,b) else fix.bearing
        )
    }

    /** Only used if Android killed the app process and cached maneuver
     * instructions are no longer in RAM. One authenticated online request
     * can refresh the selected route from a fresh genuine position.
     */
    private fun refreshRestoredTurns(fix:State.NavFix?,now:Long){
        val selected=candidates.getOrNull(chosen)?:return
        if(!active || selected.maneuvers.isNotEmpty() || savedRouteRefreshAttempted ||
            fix==null || now-fix.elapsedMs !in 0L..10000L ||
            fix.source!="GPS" || fix.accuracy>50f || State.dr ||
            AirAlerts.autoNavigationAlertActive())return
        val dest=destination?:return
        savedRouteRefreshAttempted=true
        client.requestAlternatives(RoutingEngine.Point(fix.lat,fix.lon),dest){result->
            if(isFinishing || isDestroyed || !active)return@requestAlternatives
            result.getOrNull()?.firstOrNull()?.let{newRoute->
                candidates=listOf(newRoute)
                chosen=0
                tracker.bind(newRoute,State.navFix)
                State.gpsFilterRoutePoints=newRoute.points
                State.currentGoogleRoute=newRoute
                drawRoutes()
                saveRouteSession(true)
                direction.text="Маршрут Google и повороты восстановлены"
            }
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
        val fix=latestDisplayed?:State.navFix?:State.approximateFix
        val route=candidates.getOrNull(chosen)
        if(active && fix!=null && route!=null) {
            val progress=route.progress(RoutingEngine.Point(fix.lat,fix.lon))
            moveGuidanceCamera(fix,route,progress,latestSpeedKmh)
            return
        }
        val p=fix?.let{LatLng(it.lat,it.lon)}
        if(p!=null)map?.animateCamera(CameraUpdateFactory.newLatLngZoom(
            p,if(fix.accuracy>120f)roughZoom(fix.accuracy) else 16f))
        else showError("Координаты пока не получены")
    }
    private val refresh=object:Runnable{
        override fun run(){
            val now=SystemClock.elapsedRealtime()
            val raw=State.navFix?.takeIf{
                now-it.elapsedMs in 0L..12000L &&
                    !it.source.contains("Ручн")
            }
            val approximate=State.approximateFix?.takeIf{
                ApproximateFixStore.usable(it,now)
            }
            val route=candidates.getOrNull(chosen)
            val obd=State.obdSpeedKmh()?.takeIf{
                State.speedUpdatedElapsedMs>0L &&
                now-State.speedUpdatedElapsedMs in 0L..3500L &&
                it.isFinite() && it in 0.0..180.0
            }
            if(::hardwareStatus.isInitialized && BuildConfig.FYT7870_HEAD_UNIT) {
                val speedFresh=State.speed!=null &&
                    now-State.speedUpdatedElapsedMs in 0L..3500L
                hardwareStatus.text=fytCapabilities.summary+" · OBD "+
                    (if(speedFresh)"✓" else "нет свежей скорости")+
                    (if(!fytCapabilities.gyro)
                        " · повороты оценочно (нет гироскопа)" else "")+
                    (if(BuildConfig.FYT_GOOGLE_KEY_FALLBACK)
                        " · Google API: проверь доступ FYT" else "")
            }
            val gpsSpeed=State.gpsSpeedKmh?.takeIf {
                State.gpsSpeedUpdatedElapsedMs>0L &&
                now-State.gpsSpeedUpdatedElapsedMs in 0L..6000L &&
                it.isFinite() && it in 0.0..180.0
            }
            val gpsTrustedForSpeed=raw!=null && raw.source=="GPS" &&
                !State.dr && !AirAlerts.autoNavigationAlertActive() &&
                raw.accuracy in 0f..30f &&
                now-raw.elapsedMs in 0L..5000L && gpsSpeed!=null
            val selectedSpeed=NavigationSpeedSelector.choose(
                State.dr,gpsTrustedForSpeed,gpsSpeed,obd)
            latestSpeedKmh=selectedSpeed.kmh
            displayedSpeedSource=selectedSpeed.source
            speedNumber.text=selectedSpeed.kmh?.roundToInt()?.toString()?:"—"
            speedSourceLabel.text=selectedSpeed.source
            speedSourceLabel.setTextColor(if(selectedSpeed.available)
                Color.rgb(140,204,255) else mutedText)
            speedBadgeBackground.setStroke(
                dp(if(selectedSpeed.source=="OBD")3 else 1),
                if(selectedSpeed.source=="OBD")
                    Color.rgb(255,65,67) else Color.argb(65,197,211,230))
            var visible=if(active)raw else raw?:approximate
            var estimated=visible?.source?.startsWith("Примерно")==true
            if(active && route!=null){
                refreshRestoredTurns(raw,now)
                val projected=tracker.update(now,obd,raw,State.networkFix,
                    State.yawDegPerSec)
                val gnssTrusted=raw!=null && raw.source=="GPS" &&
                    !State.dr && !AirAlerts.autoNavigationAlertActive() &&
                    raw.accuracy in 0f..50f &&
                    (gpsSpeed?:0.0)<=180.0
                if(gnssTrusted && raw!=null)tracker.alignWithValidatedGps(raw)
                // NavService has already validated accepted GNSS samples.
                // Do not block real navigation when OBD is disconnected:
                // the inertial cursor cannot advance without live OBD.
                visible=if(gnssTrusted && raw!=null)
                    snapAcceptedGps(raw,route) else projected?:raw
                estimated=!gnssTrusted
                if(visible!=null){
                    val progress=route.progress(RoutingEngine.Point(visible.lat,visible.lon))
                    showTurnAndTrip(route,progress,latestSpeedKmh,gnssTrusted,now)
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
            updateAccuracyCircle(visible)
            if(!active && visible!=null && destination==null){
                if(visible.source.startsWith("Примерно")){
                    status.text="Приблизительное положение · ±"+
                        formatDistance(visible.accuracy.toDouble())
                    direction.text="Круг — погрешность Android; жду более точный GPS"
                }else{
                    status.text="Положение найдено · "+
                        formatDistance(visible.accuracy.toDouble())+" точность"
                    direction.text=State.sourceDescription
                }
                maybeCenterStartupPosition(visible)
            }
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
                        .alpha(0.88f)
                        .rotation(displayHeading!!.toFloat()).zIndex(12f)
                        .title("Автомобиль"))
                }else{
                    car?.position=p
                    car?.rotation=displayHeading!!.toFloat()
                }
                car?.title=when {
                    visible.source.startsWith("Примерно") ->
                        "Положение приблизительное (±"+
                            formatDistance(visible.accuracy.toDouble())+")"
                    estimated->"Расчётная позиция · GPS ненадёжен"
                    else->"Автомобиль · GPS/сеть"
                }
            }
            updateFytNotices()
            handler.postDelayed(this,1000L)
        }
    }
    override fun onResume(){
        super.onResume()
        if(awaitingDiagnosticReturn) {
            awaitingDiagnosticReturn=false
            FytCrashDiagnostics.finishOnReturn(this)
            oneShotDiagnosticButton?.text="Лог"
            Toast.makeText(this,
                "Проверка завершена. Нажми «Лог», чтобы отправить результат.",
                Toast.LENGTH_LONG).show()
        }
        if(::mapView.isInitialized){
            mapView.onResume()
            handler.removeCallbacks(refresh);handler.post(refresh)
            if(hasLocationPermission()){
                startupProbe.start()
                startMonitoring()
            }
        }
    }
    override fun onPause(){
        handler.removeCallbacks(refresh)
        startupProbe.stop()
        saveRouteSession()
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
        searchPopup?.dismiss()
        searchPopup=null
        searchRequest++
        geocodeWorker.shutdownNow()
        saveRouteSession()
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
    private var lastTurnMatched=-1
    private var lastTurnMatchMs=0L
    val progressMeters:Double get()=distance
    fun reset(){
        route=null;lengths=DoubleArray(0);distance=0.0
        lastTime=0L;lastFixTime=0L;yaw=0.0
        lastTurnMatched=-1;lastTurnMatchMs=0L
    }
    fun bind(r:RoutingEngine.Route,initial:State.NavFix?,
             restoredProgressMeters:Double?=null){
        reset();route=r
        lengths=DoubleArray(r.points.size)
        for(i in 1 until r.points.size)lengths[i]=lengths[i-1]+
            RoutingEngine.meters(r.points[i-1],r.points[i])
        distance=(restoredProgressMeters?.takeIf{it.isFinite()}
            ?:initial?.let{project(it.lat,it.lon,true)?.first}
            ?:0.0).coerceIn(0.0,lengths.last())
        lastTime=SystemClock.elapsedRealtime()
    }
    /** Keep the inertial cursor synchronized to accepted, fresh GPS while
     * satellite navigation is working. Otherwise an OBD-less journey would
     * leave the DR fallback cursor far behind the actual car position.
     */
    fun alignWithValidatedGps(fix:State.NavFix) {
        if(fix.source!="GPS" || fix.accuracy !in 0f..50f)return
        val match=project(fix.lat,fix.lon,true)?:return
        if(match.second>max(40.0,fix.accuracy*1.6))return
        distance=match.first.coerceIn(0.0,lengths.lastOrNull()?:0.0)
        lastFixTime=max(lastFixTime,fix.elapsedMs)
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
        if(abs(yaw)>27 && obd!=null && obd>4.0 &&
            now-lastTurnMatchMs>=3500L) {
            val step=r.maneuvers.firstOrNull{
                val idx=it.shapeIndex.coerceIn(1,r.points.lastIndex-1)
                val before=bearing(r.points[idx-1],r.points[idx])
                val after=bearing(r.points[idx],r.points[idx+1])
                val delta=((after-before+540.0)%360.0)-180.0
                abs(delta)>25 && sign(delta)==sign(yaw) &&
                    it.shapeIndex!=lastTurnMatched &&
                    lengths[idx] in (distance-25.0)..(distance+55.0)
            }
            if(step!=null){
                val idx=step.shapeIndex.coerceIn(0,r.points.lastIndex)
                val delta=(lengths[idx]-distance).coerceIn(-5.0,10.0)
                // A turn is evidence, not an absolute position.
                // Apply a bounded smooth correction; never teleport.
                distance=(distance+delta*0.70).coerceIn(0.0,lengths.last())
                lastTurnMatched=step.shapeIndex
                lastTurnMatchMs=now
                yaw=0.0
            }
        }
        // Use the most recent *independently validated* GPS samples,
        // including during OBD dead reckoning. The DR position must NOT
        // feed back into itself as independent evidence.
        for(candidate in listOfNotNull(State.recentTrustedGps,network,fix)){
            if(candidate.elapsedMs<=lastFixTime ||
                now-candidate.elapsedMs !in 0L..15000L ||
                candidate.accuracy !in 1f..150f)continue
            if(candidate.source!="GPS" && candidate.source!="Wi-Fi/сеть")continue
            if(candidate.source=="GPS" && candidate.accuracy>50f)continue
            val matched=project(candidate.lat,candidate.lon)?:continue
            val along=matched.first
            val lateral=matched.second
            if(lateral>max(70.0,candidate.accuracy*1.8))continue
            if(abs(along-distance)>max(90.0,candidate.accuracy*2.5))continue
            val confidence=if(candidate.source=="GPS")
                if(candidate.accuracy<=25f)0.12 else 0.06
                else 0.035
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
    private fun project(lat:Double,lon:Double,
                        entireRoute:Boolean=false):Pair<Double,Double>? {
        val r=route?:return null
        var min=Double.POSITIVE_INFINITY;var along=0.0
        for(i in 0 until r.points.lastIndex){
            // Avoid snapping across a later loop of the same route.
            if(!entireRoute &&
                (lengths[i+1]<distance-300.0 || lengths[i]>distance+300.0))continue
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
