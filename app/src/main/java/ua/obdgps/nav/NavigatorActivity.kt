package ua.obdgps.nav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.*

/**
 * Basic in-app automotive navigation.
 * Renders State.navFix directly: GPS, genuine network Wi-Fi/cell fixes,
 * or inertial DeadReckoner. Never reads phone GPS directly.
 */
class NavigatorActivity : Activity() {
    private val pageBackgroundColor=Color.rgb(27,31,38)
    private val panelColor=Color.rgb(47,54,65)
    private val textColor=Color.rgb(238,242,248)
    private val secondary=Color.rgb(170,191,210)
    private val handler=Handler(Looper.getMainLooper())
    private val engine=RoutingEngine()
    private val placeSearch=PlaceSearch(this)
    private lateinit var map:InertialTrackMapView
    private lateinit var summary:TextView
    private lateinit var instruction:TextView
    private lateinit var source:TextView
    private lateinit var pickStatus:TextView
    private lateinit var voiceButton:Button
    private var selectedDestination:RoutingEngine.Point?=null
    private var manualOrigin:RoutingEngine.Point?=null
    private var plan:RoutingEngine.Route?=null
    private var requestingRoute=false
    private var routeGeneration=0
    private var active=false
    private var pickMode="DEST"
    private var lastRerouteMs=0L
    private var lastVoiceKey=""
    private var voiceEnabled=true
    private var ttsReady=false
    private var tts:TextToSpeech?=null

    private fun dp(n:Int)=(resources.displayMetrics.density*n+0.5f).toInt()
    private fun styledButton(label:String,onClick:()->Unit)=Button(this).apply {
        text=label
        isAllCaps=false
        textSize=13f
        setTextColor(textColor)
        background=GradientDrawable().apply {
            setColor(panelColor)
            cornerRadius=dp(8).toFloat()
        }
        setPadding(dp(5),dp(7),dp(5),dp(7))
        setOnClickListener{onClick()}
    }
    private fun label(value:String,size:Float,color:Int=textColor)=TextView(this).apply {
        text=value
        textSize=size
        setTextColor(color)
    }
    private fun row(vararg buttons:Button):LinearLayout {
        val container=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            setPadding(0,dp(4),0,dp(4))
        }
        buttons.forEach{b->
            container.addView(b,LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{
                setMargins(dp(2),0,dp(2),0)
            })
        }
        return container
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=pageBackgroundColor
        window.navigationBarColor=pageBackgroundColor
        window.decorView.systemUiVisibility=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor)
            setPadding(dp(12),dp(6),dp(12),dp(8))
            setOnApplyWindowInsetsListener{v,insets->
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout())
                v.setPadding(bars.left+dp(12),bars.top+dp(6),
                    bars.right+dp(12),bars.bottom+dp(8))
                insets
            }
        }

        val title=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        title.addView(label("Навигатор · OBD GPS",20f).apply {
            typeface=Typeface.DEFAULT_BOLD
        },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        title.addView(styledButton("Назад"){finish()},
            LinearLayout.LayoutParams(dp(80),dp(42)))
        root.addView(title)

        source=label("Источники координат: ожидание",12f,secondary)
        source.setPadding(dp(2),dp(3),dp(2),dp(5))
        root.addView(source)

        pickStatus=label("Коснись карты, чтобы выбрать пункт назначения",13f,secondary)
        pickStatus.setPadding(dp(2),dp(2),dp(2),dp(3))
        root.addView(pickStatus)

        root.addView(row(
            styledButton("Цель на карте") {
                pickMode="DEST"
                pickStatus.text="Коснись карты: выбрать пункт назначения"
            },
            styledButton("Старт на карте") {
                pickMode="START"
                pickStatus.text="Коснись карты: отметить старт автомобиля"
            },
            styledButton("Поиск адреса") {showAddressSearch()}
        ))

        val mapContainer=FrameLayout(this)
        map=InertialTrackMapView(this)
        map.enableNavigationMode{lat,lon->onMapPicked(lat,lon)}
        mapContainer.addView(map)
        val zoom=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(2),dp(2),dp(2),dp(2))
        }
        zoom.addView(styledButton("+"){map.changeZoom(1)},
            LinearLayout.LayoutParams(dp(48),dp(46)))
        zoom.addView(styledButton("−"){map.changeZoom(-1)},
            LinearLayout.LayoutParams(dp(48),dp(46)).apply{topMargin=dp(5)})
        zoom.addView(styledButton("◎"){map.followCar()},
            LinearLayout.LayoutParams(dp(48),dp(46)).apply{topMargin=dp(5)})
        mapContainer.addView(zoom,FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.RIGHT or Gravity.TOP).apply{setMargins(0,dp(12),dp(10),0)})
        root.addView(mapContainer,LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,0,1f))

        summary=label("Маршрут ещё не построен",14f,textColor)
        summary.setPadding(dp(3),dp(5),dp(3),dp(2))
        root.addView(summary)
        instruction=label("Выбери цель и нажми «Маршрут»",17f,textColor).apply {
            typeface=Typeface.DEFAULT_BOLD
            maxLines=3
        }
        root.addView(instruction)

        root.addView(row(
            styledButton("Маршрут"){buildRoute(false)},
            styledButton("Начать ведение"){startGuidance()},
            styledButton("Сброс"){stopGuidance()}
        ))
        voiceButton=styledButton("Голос: вкл"){
            voiceEnabled=!voiceEnabled
            voiceButton.text=if(voiceEnabled)"Голос: вкл" else "Голос: выкл"
            if(!voiceEnabled)tts?.stop()
        }
        root.addView(row(
            styledButton("Ведение по датчикам"){sendService(NavService.START_DR)},
            styledButton("Вернуться к GPS"){sendService(NavService.STOP_DR)},
            voiceButton
        ))
        root.addView(label(
            "© OpenStreetMap contributors · Маршруты Valhalla (демо). " +
                "Выбор точки — касанием карты. Не используй меню во время движения.",
            10f,secondary).apply{gravity=Gravity.CENTER})
        setContentView(root)

        NavigatorPrefs(this).manualStart?.let {
            State.manualStart=it
            manualOrigin=RoutingEngine.Point(it.lat,it.lon)
            pickStatus.text="Старт сохранён: "+format(it.lat)+", "+format(it.lon)+
                "; курс "+it.bearing.toInt()+"°"
        }
        tts=TextToSpeech(this){status->
            if(status==TextToSpeech.SUCCESS) {
                ttsReady=true
                tts?.language=Locale("ru","RU")
            }
        }
    }

    private fun onMapPicked(lat:Double,lon:Double) {
        val point=RoutingEngine.Point(lat,lon)
        when(pickMode) {
            "START" -> {
                manualOrigin=point
                pickMode="BEARING"
                map.centerAt(lat,lon)
                pickStatus.text="Старт выбран. Коснись точки ПО КУРСУ движения "+
                    "(не ближе 15 метров), чтобы задать направление автомобиля"
            }
            "BEARING" -> {
                val origin=manualOrigin
                if(origin==null) {pickMode="START";return}
                if(RoutingEngine.meters(origin,point)<15.0) {
                    pickStatus.text="Нужно выбрать точку по курсу дальше 15 м"
                    return
                }
                val latitude=Math.toRadians((origin.lat+point.lat)/2)
                val dx=(point.lon-origin.lon)*cos(latitude)
                val dy=point.lat-origin.lat
                val bearing=(Math.toDegrees(atan2(dx,dy))+360.0)%360.0
                val start=NavigatorPrefs.ManualStart(origin.lat,origin.lon,bearing)
                NavigatorPrefs(this).manualStart=start
                State.manualStart=start
                sendService(NavService.SET_MANUAL_START,origin.lat,origin.lon,bearing)
                pickMode="DEST"
                pickStatus.text="Старт и курс "+bearing.toInt()+"° сохранены. "+
                    "Теперь выбери цель на карте."
            }
            else -> {
                selectedDestination=point
                State.navigationDestination=lat to lon
                map.centerAt(lat,lon)
                pickStatus.text="Цель выбрана: "+format(lat)+", "+format(lon)
                if(State.navFix!=null || manualOrigin!=null)buildRoute(false)
            }
        }
    }

    private fun showAddressSearch() {
        val field=EditText(this).apply {
            hint="Адрес или 50.4501, 30.5234"
            isSingleLine=true
        }
        val wrapper=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),0,dp(20),0)
        }
        wrapper.addView(field)
        AlertDialog.Builder(this).setTitle("Найти пункт назначения")
            .setView(wrapper)
            .setPositiveButton("Поиск"){_,_->
                pickStatus.text="Поиск адреса..."
                placeSearch.search(field.text.toString()){result->
                    result.fold(
                        onSuccess={items->
                            if(items.isEmpty()) {
                                pickStatus.text="Ничего не найдено"
                            } else {
                                AlertDialog.Builder(this).setTitle("Выбери адрес")
                                    .setItems(items.map{it.label}.toTypedArray()){_,index->
                                        val p=items[index].coordinate
                                        selectedDestination=p
                                        State.navigationDestination=p.lat to p.lon
                                        map.centerAt(p.lat,p.lon)
                                        pickMode="DEST"
                                        pickStatus.text="Цель: "+items[index].label
                                        buildRoute(false)
                                    }.setNegativeButton("Отмена",null).show()
                            }
                        },
                        onFailure={error->
                            val message=error.localizedMessage?: "Нет ответа сервиса адресов"
                            pickStatus.text="Поиск адреса: $message"
                            AlertDialog.Builder(this)
                                .setTitle("Не удалось найти адрес")
                                .setMessage(message+"\n\nПовтори поиск, "+
                                    "введи координаты (например, 50.4501, 30.5234) "+
                                    "или выбери точку касанием карты.")
                                .setPositiveButton("Повторить поиск"){_,_->showAddressSearch()}
                                .setNegativeButton("Закрыть",null).show()
                        }
                    )
                }
            }.setNegativeButton("Отмена",null).show()
    }

    private fun currentFix():State.NavFix? {
        val fix=State.navFix ?:return null
        if(fix.source=="Ручной старт" || fix.source=="Ручная точка")return fix
        return if(SystemClock.elapsedRealtime()-fix.elapsedMs<=15_000L)fix else null
    }

    private fun origin():RoutingEngine.Point? {
        val fixed=manualOrigin
        // An explicit manually pinned origin is used for route planning
        // until it is cleared; live marker still tracks the service.
        if(fixed!=null && !active)return fixed
        val f=currentFix()
        return if(f!=null)RoutingEngine.Point(f.lat,f.lon) else fixed
    }

    private fun buildRoute(reroute:Boolean) {
        val dest=selectedDestination
        val start=if(reroute)currentFix()?.let{RoutingEngine.Point(it.lat,it.lon)}
            else origin()
        if(dest==null) {
            summary.text="Выбери цель на карте или найди адрес"
            return
        }
        if(start==null) {
            summary.text="Нет исходных координат. Выбери старт и курс на карте."
            return
        }
        if(requestingRoute) return
        if(RoutingEngine.meters(start,dest)<40) {
            summary.text="Точка назначения слишком близко к старту"
            return
        }
        requestingRoute=true
        val requestId=++routeGeneration
        summary.text=if(reroute)"Перестраиваю маршрут..." else "Построение маршрута..."
        engine.request(start,dest){result->
            if(requestId!=routeGeneration)return@request
            requestingRoute=false
            result.fold(
                onSuccess={value->
                    plan=value
                    State.activeRouting=active
                    pickStatus.text="Маршрут построен. "+(value.lengthKm).roundToInt()+" км"
                    if(active) speak("Маршрут обновлён")
                },
                onFailure={e->
                    summary.text="Маршрут не получен: "+(e.localizedMessage?:"нет сети")
                    if(plan!=null)summary.text=summary.text.toString()+". Прежний маршрут сохранён"
                }
            )
        }
    }

    private fun startGuidance() {
        if(plan==null) {
            buildRoute(false)
            summary.text="Построй маршрут, затем начни ведение"
            return
        }
        active=true
        State.activeRouting=true
        lastVoiceKey=""
        speak("Ведение по маршруту начато")
    }

    private fun stopGuidance() {
        active=false
        State.activeRouting=false
        routeGeneration++
        requestingRoute=false
        plan=null
        selectedDestination=null
        State.navigationDestination=null
        lastVoiceKey=""
        summary.text="Маршрут сброшен"
        instruction.text="Выбери новый пункт назначения"
        tts?.stop()
    }

    private fun speak(text:String) {
        if(voiceEnabled&&ttsReady&&text.isNotBlank())
            tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"nav-"+System.nanoTime())
    }

    private fun maybeSpeakManeuver(progress:RoutingEngine.Progress) {
        val turn=progress.nextTurn ?:return
        if(progress.arrived) {
            if(lastVoiceKey!="arrived") {
                speak("Вы прибыли к пункту назначения")
                lastVoiceKey="arrived"
                active=false
                State.activeRouting=false
            }
            return
        }
        val index=turn.shapeIndex
        val band=when {
            progress.nextTurnMeters<=45.0 -> "now"
            progress.nextTurnMeters<=180.0 -> "soon"
            else -> return
        }
        val key=index.toString()+"-"+band
        if(key==lastVoiceKey)return
        lastVoiceKey=key
        speak(turn.voice.ifBlank{turn.instruction})
    }

    private fun sendService(action:String,lat:Double?=null,lon:Double?=null,
                            bearing:Double?=null) {
        val intent=Intent(this,NavService::class.java).setAction(action)
        if(lat!=null&&lon!=null&&bearing!=null) {
            intent.putExtra("lat",lat)
            intent.putExtra("lon",lon)
            intent.putExtra("bearing",bearing)
        }
        try {
            startForegroundService(intent)
        } catch(e:Exception) {
            summary.text="Невозможно выполнить команду: "+(e.localizedMessage?:"ошибка")
        }
    }

    private fun liveCarSpeed():Double? {
        val now=SystemClock.elapsedRealtime()
        // The corrected ELM327/OBD value is the primary speed source.
        val obd=State.obdSpeedKmh()
        if(obd!=null && obd.isFinite() && State.speedUpdatedElapsedMs>0L &&
            now-State.speedUpdatedElapsedMs<3500L)return obd
        // Real GPS velocity can drive the camera when OBD is disconnected.
        val gps=State.gpsSpeedKmh
        if(gps!=null && gps.isFinite() && State.gpsSpeedUpdatedElapsedMs>0L &&
            now-State.gpsSpeedUpdatedElapsedMs<6000L)return gps
        return null
    }

    private val refresh=object:Runnable {
        override fun run() {
            val fix=currentFix()
            val route=plan
            val speed=liveCarSpeed()
            map.updateFromNavigation(fix,route?.points?:emptyList(),
                selectedDestination,manualOrigin,speed)
            source.text=State.sourceDescription+" · "+
                if(State.dr)"DR + OBD" else "обычное местоположение"
            if(route!=null) {
                if(fix!=null) {
                    val progress=route.progress(RoutingEngine.Point(fix.lat,fix.lon))
                    val distance=if(progress.remainingMeters>=1000)
                        String.format(Locale.getDefault(),"%.1f км",progress.remainingMeters/1000)
                    else progress.remainingMeters.roundToInt().toString()+" м"
                    summary.text=distance+" · "+(progress.secondsRemaining/60).toString()+
                        " мин · "+(if(active)"ведение" else "маршрут готов")
                    instruction.text=if(progress.arrived)"Вы прибыли"
                        else (progress.nextTurn?.let {
                            "Через "+progress.nextTurnMeters.roundToInt()+" м: "+
                                it.instruction
                        }?:"Продолжай движение по маршруту")
                    if(active) {
                        maybeSpeakManeuver(progress)
                        val now=SystemClock.elapsedRealtime()
                        if(progress.lateralMeters>max(120.0,fix.accuracy*2.3) &&
                            now-lastRerouteMs>65_000L && !requestingRoute &&
                            fix.accuracy<=90f && fix.source!="Ручная точка" &&
                            fix.source!="Ручной старт") {
                            lastRerouteMs=now
                            buildRoute(true)
                        }
                    }
                } else {
                    instruction.text="Нет свежего положения — маршрут сохранён"
                    summary.text="Ожидаем GPS/Wi-Fi или ручной старт"
                }
            }
            handler.postDelayed(this,1000L)
        }
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
    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun format(v:Double)=String.format(Locale.US,"%.5f",v)
}
