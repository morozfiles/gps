package ua.obdgps.nav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

/**
 * Native light vector map, rendered completely offline from a Mapsforge .map
 * file. Position is State.navFix from the existing GPS/OBD/DR service.
 * Places are searched in a local .poi database, never sent to the internet.
 * Vehicle route computation still uses the existing online Valhalla backend.
 */
class OfflineVectorNavigatorActivity:Activity() {
    private val bg=Color.rgb(27,31,38)
    private val ink=Color.rgb(238,243,249)
    private val secondary=Color.rgb(170,191,210)
    private val handler=Handler(Looper.getMainLooper())
    private val searchWorker=Executors.newSingleThreadExecutor { r->
        Thread(r,"Mapsforge-POI-Search").apply{isDaemon=true}
    }
    private val routing=RoutingEngine()
    private lateinit var mapView:MapView
    private lateinit var info:TextView
    private lateinit var guidance:TextView
    private lateinit var arrow:View
    private lateinit var alerts:AirAlertIndicatorsView
    private var vectorMap:MapDataStore?=null
    private var loadedMapSignature=""
    private var loadedRegion=""
    private var routeLine:Polyline?=null
    private var route:RoutingEngine.Route?=null
    private var dest:RoutingEngine.Point?=null
    private var follow=true
    private var active=false
    private var picking=false
    private var lastGpsPosition:RoutingEngine.Point?=null
    private var heading=0.0
    private var headingReady=false
    private var headingTarget=0.0
    private var turnCandidate:Double?=null
    private var turnCandidateSince=0L
    private var smoothedSpeed=Double.NaN
    private var zoomCandidate=17
    private var zoomSince=0L
    private var lastAutoZoom=0L
    private var manualZoomUntil=0L
    private var lastReroute=0L
    private val inertialRouteMatcher=InertialRouteMatcher()
    private var lastRouteWarning=InertialRouteMatcher.Warning.NONE
    private var lastWarnedAt=0L
    private var alertVoice:TextToSpeech?=null
    private var alertVoiceReady=false
    private var requesting=false
    private var lastTick=0L

    private fun dp(x:Int)=(resources.displayMetrics.density*x+0.5f).toInt()
    private fun button(s:String,click:()->Unit):Button=Button(this).apply{
        text=s
        textSize=11f
        isAllCaps=false
        maxLines=2
        gravity=Gravity.CENTER
        setTextColor(ink)
        minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0
        background=GradientDrawable().apply {
            cornerRadius=dp(7).toFloat()
            setColor(Color.rgb(47,54,65))
        }
        setPadding(dp(2),0,dp(2),0)
        setOnClickListener{click()}
    }
    private fun row(vararg buttons:Button):LinearLayout=
        LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(0,dp(2),0,dp(2))
            buttons.forEach {
                addView(it,LinearLayout.LayoutParams(0,dp(40),1f).apply{
                    setMargins(dp(2),0,dp(2),0)
                })
            }
        }
    private fun label(text:String,size:Float=13f):TextView =
        TextView(this).apply{
            this.text=text
            textSize=size
            setTextColor(ink)
        }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AirAlerts.initialize(this)
        if(OfflineRegionPacks.mapFiles(this).isEmpty()){
            AlertDialog.Builder(this).setTitle("Нужна офлайн-карта")
                .setMessage("Карта Украины ещё не найдена. В разделе «Офлайн-карты» " +
                    "можно проверить сохранённые файлы из прошлой версии " +
                    "или скачать только недостающую карту и базу адресов.")
                .setPositiveButton("Скачать Украину"){_,_->
                    startActivity(Intent(this,OfflineMapsActivity::class.java)
                        .putExtra("download_ukraine",true))
                    finish()
                }
                .setNeutralButton("Проверить сохранённое"){_,_->
                    startActivity(Intent(this,OfflineMapsActivity::class.java))
                    finish()
                }.setNegativeButton("Назад"){_,_->finish()}
                .setOnCancelListener{finish()}.show()
            return
        }
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(8),dp(5),dp(8),dp(6))
            setOnApplyWindowInsetsListener {view,insets->
                val b=insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout())
                view.setPadding(dp(8)+b.left,dp(5)+b.top,
                    dp(8)+b.right,dp(6)+b.bottom)
                insets
            }
        }
        val title=LinearLayout(this).apply{
            gravity=Gravity.CENTER_VERTICAL
            orientation=LinearLayout.HORIZONTAL
        }
        title.addView(label("Офлайн OBD GPS",17f).apply{
            typeface=Typeface.DEFAULT_BOLD
        },LinearLayout.LayoutParams(0,dp(49),1f))
        alerts=AirAlertIndicatorsView(this)
        title.addView(alerts,LinearLayout.LayoutParams(dp(168),dp(53)))
        root.addView(title)
        info=label("Чтение офлайн-карты...",12f).apply{
            setTextColor(secondary)
            isSingleLine=true
        }
        root.addView(info)
        val frame=FrameLayout(this)
        try{
            AndroidGraphicFactory.createInstance(application)
            mapView=MapView(this)
            mapView.setBuiltInZoomControls(false)
            // Mapsforge 0.25 natively recognizes a two-finger twist while
            // preserving its pinch zoom, pan and map-feature hit testing.
            mapView.getTouchGestureHandler().setRotationEnabled(true)
            mapView.getMapScaleBar().setVisible(false)
            mapView.setMapViewCenterY(0.68f)
            val files=OfflineRegionPacks.mapFiles(this)
            loadedRegion=OfflineRegionPacks.selected(this)
            loadedMapSignature=signature(files)
            val store:MapDataStore=if(files.size==1)MapFile(files.first())
            else MultiMapDataStore(MultiMapDataStore.DataPolicy.DEDUPLICATE).apply {
                files.forEachIndexed { index,file->
                    val detail=MapFile(file)
                    detail.setPriority(files.size-index)
                    addMapDataStore(detail,false,false)
                }
            }
            vectorMap=store
            val tileCache=AndroidUtil.createTileCache(this,"offline-vector",
                mapView.getModel().displayModel.getTileSize(),1.25f,
                mapView.getModel().frameBufferModel.getOverdrawFactor())
            val renderer=object:TileRendererLayer(tileCache,store,
                mapView.getModel().mapViewPosition,AndroidGraphicFactory.INSTANCE){
                override fun onTap(pos:LatLong,layerXY:Point,tapXY:Point):Boolean {
                    if(!picking)return false
                    dest=RoutingEngine.Point(pos.latitude,pos.longitude)
                    State.navigationDestination=pos.latitude to pos.longitude
                    picking=false
                    route=null
                    active=false
                    inertialRouteMatcher.reset()
                    State.activeRouting=false
                    State.gpsFilterRoutePoints=emptyList()
                    clearRoute()
                    info.text="Цель выбрана на карте"
                    return true
                }
            }
            renderer.setXmlRenderTheme(MapsforgeThemes.MOTORIDER)
            mapView.getLayerManager().getLayers().add(renderer)
            val region=OfflineRegionPacks.region(OfflineRegionPacks.selected(this))
            val point=State.navFix
            mapView.setCenter(LatLong(point?.lat?:region?.lat?:50.4501,
                point?.lon?:region?.lon?:30.5234))
            mapView.setZoomLevel(17.toByte())
            mapView.setOnTouchListener{_,event->
                if(!picking && (event.actionMasked==MotionEvent.ACTION_DOWN ||
                    event.actionMasked==MotionEvent.ACTION_POINTER_DOWN)){
                    // A manual twist must stay in effect instead of being
                    // overwritten by the heading-up auto-follow refresh.
                    follow=false
                    manualZoomUntil=SystemClock.elapsedRealtime()+60_000L
                }
                // Let Mapsforge's own pinch and rotation detectors receive
                // the original event. Never consume this callback.
                false
            }
            frame.addView(mapView,FrameLayout.LayoutParams(-1,-1))
        }catch(e:Exception){
            AlertDialog.Builder(this).setTitle("Ошибка векторной карты")
                .setMessage((e.localizedMessage?:"Не удалось открыть .map")+
                    "\nПроверь сохранённую карту Украины или скачай недостающий файл.")
                .setPositiveButton("Карты"){_,_->
                    startActivity(Intent(this,OfflineMapsActivity::class.java))
                    finish()
                }.setNegativeButton("Закрыть"){_,_->finish()}.show()
            return
        }
        arrow=object:View(this) {
            private val shape=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                color=Color.rgb(22,135,245);style=Paint.Style.FILL
            }
            private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=4.5f
            }
            override fun onDraw(c:Canvas) {
                if(!follow||lastGpsPosition==null)return
                val x=width/2f
                val y=height*.68f
                c.drawCircle(x,y,dp(27).toFloat(),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply{
                        color=Color.argb(40,20,130,250)})
                val p=Path().apply{
                    moveTo(x,y-dp(31));lineTo(x+dp(16),y+dp(19))
                    lineTo(x,y+dp(10));lineTo(x-dp(16),y+dp(19));close()
                }
                c.drawPath(p,edge)
                c.drawPath(p,shape)
            }
        }
        arrow.isClickable=false
        arrow.isFocusable=false
        frame.addView(arrow,FrameLayout.LayoutParams(-1,-1))
        frame.addView(button("+"){adjustZoom(1)},
            FrameLayout.LayoutParams(dp(45),dp(45),Gravity.TOP or Gravity.RIGHT).apply{
                topMargin=dp(8);rightMargin=dp(8)
            })
        frame.addView(button("−"){adjustZoom(-1)},
            FrameLayout.LayoutParams(dp(45),dp(45),Gravity.TOP or Gravity.RIGHT).apply{
                topMargin=dp(56);rightMargin=dp(8)
            })
        frame.addView(button("◎"){followCar()},
            FrameLayout.LayoutParams(dp(45),dp(45),Gravity.TOP or Gravity.RIGHT).apply{
                topMargin=dp(104);rightMargin=dp(8)
            })
        frame.addView(label("© OpenStreetMap contributors",10f).apply {
            setTextColor(Color.rgb(56,68,80))
            setBackgroundColor(Color.argb(185,255,255,255))
            setPadding(dp(3),dp(2),dp(3),dp(2))
        },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.LEFT).apply{
            topMargin=dp(3);leftMargin=dp(4)
        })
        guidance=label("Адреса доступны по .poi · Маршруты пока онлайн",12f)
        guidance.setPadding(dp(6),dp(4),dp(6),dp(4))
        guidance.setBackgroundColor(Color.argb(230,35,45,58))
        frame.addView(guidance,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{
            bottomMargin=dp(5)
        })
        root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(row(
            button("Поиск адреса"){searchAddress()},
            button("Цель на карте"){
                picking=true
                info.text="Коснись карты, чтобы выбрать точку назначения"
            },
            button("Маршрут"){buildRoute(false)}
        ))
        root.addView(row(
            button("Начать ведение") {
                if(route==null){
                    buildRoute(false)
                }else{
                    active=true
                    inertialRouteMatcher.reset()
                    lastRouteWarning=InertialRouteMatcher.Warning.NONE
                    State.activeRouting=true
                    State.gpsFilterRoutePoints=route!!.points
                    followCar()
                    info.text="Ведение по маршруту · GPS / OBD / DR"
                }
            },
            button("Ведение по датчикам"){
                try{startForegroundService(Intent(this,NavService::class.java)
                    .setAction(NavService.START_DR))}
                catch(_:Exception){}
            },
            button("Офлайн-карты"){
                startActivity(Intent(this,OfflineMapsActivity::class.java))
            }
        ))
        setContentView(root)
        alertVoice=TextToSpeech(this){result->
            if(result==TextToSpeech.SUCCESS){
                alertVoiceReady=true
                alertVoice?.language=Locale("ru","RU")
            }
        }
    }

    private fun adjustZoom(by:Int){
        manualZoomUntil=SystemClock.elapsedRealtime()+60_000L
        mapView.setZoomLevel((mapView.getModel().mapViewPosition.getZoomLevel()
            .toInt()+by).coerceIn(11,19).toByte())
    }

    private fun followCar(){
        follow=true
        manualZoomUntil=0L
        lastGpsPosition?.let{mapView.setCenter(LatLong(it.lat,it.lon))}
        arrow.invalidate()
    }

    private fun searchAddress(){
        val input=EditText(this).apply{
            hint="Город, улица, дом"
            isSingleLine=true
        }
        val history=NavigatorPrefs(this).recentAddresses()
        val recentTitles=history.map{it.label}
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(15),0,dp(15),0)
            addView(input)
            addView(label("Последние адреса:",12f))
        }
        val dialog=AlertDialog.Builder(this)
            .setTitle("Поиск без интернета")
            .setView(content)
            .setPositiveButton("Найти",null)
            .setNegativeButton("Отмена",null).create()
        history.forEach{recent->
            content.addView(button(recent.label){
                dialog.dismiss()
                selectAddress(recent.label,
                    RoutingEngine.Point(recent.lat,recent.lon))
            },LinearLayout.LayoutParams(-1,dp(36)))
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query=input.text.toString().trim()
                if(!PlaceSearch.isAllowedAddress(query)){
                    input.error="Введите адрес на русском или украинском"
                    return@setOnClickListener
                }
                dialog.dismiss()
                info.text="Поиск в локальной базе POI..."
                searchWorker.execute {
                    val found=OfflineVectorStore.findPlaces(this,query,10)
                    handler.post {
                        if(found.isEmpty()){
                            AlertDialog.Builder(this)
                                .setTitle("Адрес не найден офлайн")
                                .setMessage(if(OfflineRegionPacks.poiFiles(this).isEmpty())
                                    "Скачай или восстанови базу адресов Украины (.poi) в «Офлайн-карты»."
                                    else "В базе POI нет этого адреса. "+
                                        "Часть номеров домов отсутствует в OpenStreetMap.")
                                .setPositiveButton("Карты"){_,_->
                                    startActivity(Intent(this,OfflineMapsActivity::class.java))
                                }
                                .setNegativeButton("Закрыть",null).show()
                        }else{
                            AlertDialog.Builder(this).setTitle("Найдено офлайн")
                                .setItems(found.map{it.label}.toTypedArray()){_,n->
                                    val x=found[n]
                                    selectAddress(x.label,RoutingEngine.Point(x.lat,x.lon))
                                }.show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun selectAddress(label:String,coordinate:RoutingEngine.Point){
        dest=coordinate
        State.navigationDestination=coordinate.lat to coordinate.lon
        NavigatorPrefs(this).rememberAddress(NavigatorPrefs.RecentAddress(
            label,coordinate.lat,coordinate.lon))
        route=null
        active=false
        inertialRouteMatcher.reset()
        lastRouteWarning=InertialRouteMatcher.Warning.NONE
        State.activeRouting=false
        State.gpsFilterRoutePoints=emptyList()
        clearRoute()
        mapView.setCenter(LatLong(coordinate.lat,coordinate.lon))
        follow=false
        info.text="Цель: "+label.take(60)
    }

    private fun clearRoute(){
        routeLine?.let{
            mapView.getLayerManager().getLayers().remove(it)
        }
        routeLine=null
    }

    private fun buildRoute(reroute:Boolean){
        val destination=dest
        if(destination==null){
            info.text="Выбери пункт назначения"
            return
        }
        val fix=State.navFix
        if(fix==null){
            info.text="Нет исходной позиции: дождись GPS/сети или задай старт"
            return
        }
        if(requesting)return
        requesting=true
        info.text="Строю маршрут через интернет..."
        routing.request(RoutingEngine.Point(fix.lat,fix.lon),destination){result->
            requesting=false
            result.fold(onSuccess={itinerary->
                route=itinerary
                inertialRouteMatcher.reset()
                lastRouteWarning=InertialRouteMatcher.Warning.NONE
                if(active){
                    State.activeRouting=true
                    State.gpsFilterRoutePoints=itinerary.points
                }
                clearRoute()
                val p=AndroidGraphicFactory.INSTANCE.createPaint().apply{
                    color=Color.rgb(11,132,246)
                    strokeWidth=dp(8).toFloat()
                    setStyle(org.mapsforge.core.graphics.Style.STROKE)
                }
                routeLine=Polyline(p,AndroidGraphicFactory.INSTANCE).apply{
                    addPoints(itinerary.points.map{it->LatLong(it.lat,it.lon)})
                }
                mapView.getLayerManager().getLayers().add(routeLine)
                guidance.text="Маршрут: "+
                    String.format(Locale.getDefault(),"%.1f км",itinerary.lengthKm)
                if(reroute)info.text="Маршрут перестроен"
                else info.text="Маршрут готов. Нажми «Начать ведение»."
            },onFailure={e->
                info.text="Маршрут не получен (нужен интернет)"
                guidance.text=e.localizedMessage?:"Нет подключения"
            })
        }
    }

    private fun shortestCourse(from:RoutingEngine.Point,along:RoutingEngine.Route?):Double?{
        if(along==null)return null
        val index=along.progress(from).nearestShapeIndex.coerceIn(
            0,along.points.size-2)
        val origin=along.points[index]
        val next=along.points[min(index+2,along.points.lastIndex)]
        val latitude=Math.toRadians((origin.lat+next.lat)*.5)
        return ((Math.toDegrees(atan2((next.lon-origin.lon)*cos(latitude),
            next.lat-origin.lat))+360.0)%360.0)
    }

    private fun angleDelta(next:Double,prev:Double):Double=
        ((next-prev+540.0)%360.0)-180.0

    private val refresh=object:Runnable{
        override fun run(){
            if(!::mapView.isInitialized)return
            alerts.refresh()
            val fix=State.navFix?.takeIf{
                SystemClock.elapsedRealtime()-it.elapsedMs<15_000 ||
                    it.source.contains("Ручная")
            }
            if(fix!=null){
                val now=SystemClock.elapsedRealtime()
                val obdFresh=State.speedUpdatedElapsedMs>0L &&
                    now-State.speedUpdatedElapsedMs<3500L
                val speed=if(obdFresh)State.obdSpeedKmh()
                    else State.gpsSpeedKmh
                // Route projection never modifies the raw service position.
                val matched=if(active && State.dr && route!=null)
                    inertialRouteMatcher.match(fix,route,
                        if(obdFresh)State.obdSpeedKmh() else null,now)
                    else {
                        inertialRouteMatcher.reset()
                        null
                    }
                val shown=matched?.displayFix?:fix
                lastGpsPosition=RoutingEngine.Point(shown.lat,shown.lon)
                if(follow){
                    mapView.setCenter(LatLong(fix.lat,fix.lon))
                    val p=lastGpsPosition!!
                    val course=if(active) {
                        if(speed!=null&&speed>=8.0)
                            shown.bearing?:shortestCourse(p,route)
                        else shortestCourse(p,route)?:shown.bearing
                    }else shown.bearing
                    if(course!=null && course.isFinite() &&
                        (speed?:0.0)>=5.0){
                        if(!headingReady){
                            heading=course
                            headingTarget=course
                            headingReady=true
                        }else{
                            val diff=abs(angleDelta(course,headingTarget))
                            if(diff<50.0){
                                headingTarget=course
                                turnCandidate=null
                            }else{
                                val c=turnCandidate
                                if(c==null||abs(angleDelta(course,c))>25){
                                    turnCandidate=course
                                    turnCandidateSince=now
                                }else if(now-turnCandidateSince>=850){
                                    headingTarget=course
                                    turnCandidate=null
                                }
                            }
                            heading+=angleDelta(headingTarget,heading)*0.19
                        }
                        mapView.rotate(Rotation(-heading.toFloat(),
                            mapView.width*.5f,mapView.height*.68f))
                    }
                    if(speed!=null && now>manualZoomUntil){
                        smoothedSpeed=if(smoothedSpeed.isNaN())speed
                            else smoothedSpeed*.78+speed*.22
                        val target=when{
                            smoothedSpeed<25->17
                            smoothedSpeed<55->16
                            smoothedSpeed<105->15
                            else->14
                        }
                        if(target!=zoomCandidate){
                            zoomCandidate=target
                            zoomSince=now
                        }
                        if(target!=mapView.getModel().mapViewPosition.getZoomLevel()
                                .toInt()&& now-zoomSince>2600&&now-lastAutoZoom>4400){
                            mapView.setZoomLevel(target.toByte())
                            lastAutoZoom=now
                        }
                    }
                }
                if(active && route!=null){
                    val progress=route!!.progress(
                        RoutingEngine.Point(shown.lat,shown.lon))
                    val warning=matched?.warning
                        ?:InertialRouteMatcher.Warning.NONE
                    if(warning!=InertialRouteMatcher.Warning.NONE){
                        guidance.text=matched!!.warningText+
                            " · отклонение "+
                            matched.rawDeviationMeters.roundToInt()+" м"
                        guidance.setTextColor(Color.rgb(255,162,111))
                        info.text=matched.warningText+
                            ". Не полагайся на привязку к маршруту."
                        if(warning!=lastRouteWarning ||
                            now-lastWarnedAt>=45_000L){
                            Toast.makeText(this@OfflineVectorNavigatorActivity,
                                matched.warningText,Toast.LENGTH_LONG).show()
                            if(alertVoiceReady){
                                alertVoice?.speak(matched.warningText,
                                    TextToSpeech.QUEUE_FLUSH,null,"route-warning")
                            }
                            lastWarnedAt=now
                        }
                    }else{
                        guidance.setTextColor(ink)
                        guidance.text=String.format(Locale.getDefault(),
                            "До цели %.1f км · далее: %s",
                            progress.remainingMeters/1000,
                            progress.nextTurn?.instruction?:"прямо")+
                            if(matched?.snapped==true)" · DR по маршруту"
                            else ""
                    }
                    lastRouteWarning=warning
                    if(progress.arrived){
                        active=false
                        inertialRouteMatcher.reset()
                        State.activeRouting=false
                        State.gpsFilterRoutePoints=emptyList()
                        info.text="Вы прибыли"
                    }else if(!State.dr && progress.lateralMeters>140 &&
                        now-lastReroute>75_000 && !requesting &&
                        fix.accuracy<70f){
                        lastReroute=now
                        buildRoute(true)
                    }
                }
                arrow.invalidate()
            }else {
                info.text="Ожидание GPS, сети или координат OBD/DR"
            }
            handler.postDelayed(this,750L)
        }
    }

    private fun signature(files:List<java.io.File>):String =
        files.joinToString("|"){"${it.absolutePath}:${it.length()}:${it.lastModified()}"}

    override fun onResume(){
        super.onResume()
        if(::mapView.isInitialized){
            val newFiles=OfflineRegionPacks.mapFiles(this)
            if(loadedRegion!=OfflineRegionPacks.selected(this) ||
                loadedMapSignature!=signature(newFiles)){
                recreate()
                return
            }
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
    }
    override fun onPause(){
        handler.removeCallbacks(refresh)
        super.onPause()
    }
    override fun onDestroy(){
        handler.removeCallbacks(refresh)
        inertialRouteMatcher.reset()
        if(active){
            State.activeRouting=false
            State.gpsFilterRoutePoints=emptyList()
        }
        alertVoice?.stop()
        alertVoice?.shutdown()
        if(::mapView.isInitialized){
            runCatching{mapView.destroyAll()}
            runCatching{vectorMap?.close()}
        }
        runCatching{AndroidGraphicFactory.clearResourceMemoryCache()}
        searchWorker.shutdown()
        super.onDestroy()
    }
}
