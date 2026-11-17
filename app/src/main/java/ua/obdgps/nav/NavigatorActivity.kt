package ua.obdgps.nav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.RecognizerIntent
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.content.ActivityNotFoundException
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
import android.widget.Toast
import android.text.TextUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private lateinit var alertsView:AirAlertIndicatorsView
    private var selectedDestination:RoutingEngine.Point?=null
    private var manualOrigin:RoutingEngine.Point?=null
    private var plan:RoutingEngine.Route?=null
    private var requestingRoute=false
    private var routeGeneration=0
    private var active=false
    private var pickMode="DEST"
    private var lastRerouteMs=0L
    private var lastVoiceKey=""
    private var voiceEnabled=false
    private val speechRequestCode=1638
    private var speechInput:EditText?=null
    private var addressSearchDialog:AlertDialog?=null
    private var ttsReady=false
    private var tts:TextToSpeech?=null

    private fun updateAlertIndicator() {
        alertsView.refresh()
    }

    private fun dp(n:Int)=(resources.displayMetrics.density*n+0.5f).toInt()
    private fun styledButton(label:String,onClick:()->Unit)=Button(this).apply {
        text=label
        isAllCaps=false
        textSize=11f
        setTextColor(textColor)
        gravity=Gravity.CENTER
        maxLines=2
        includeFontPadding=false
        minHeight=0
        minimumHeight=0
        minWidth=0
        minimumWidth=0
        background=GradientDrawable().apply {
            setColor(panelColor)
            cornerRadius=dp(6).toFloat()
        }
        setPadding(dp(2),0,dp(2),0)
        setOnClickListener{onClick()}
    }
    private fun mapControl(label:String,onClick:()->Unit):Button =
        Button(this).apply {
            text=label
            isAllCaps=false
            textSize=21f
            gravity=Gravity.CENTER
            includeFontPadding=false
            setTextColor(Color.WHITE)
            minHeight=0
            minimumHeight=0
            minWidth=0
            minimumWidth=0
            background=GradientDrawable().apply {
                cornerRadius=dp(11).toFloat()
                setColor(Color.argb(164,30,39,53))
                setStroke(dp(1),Color.argb(160,210,225,244))
            }
            setPadding(0,0,0,0)
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
            setPadding(0,dp(2),0,dp(2))
        }
        buttons.forEach{b->
            container.addView(b,LinearLayout.LayoutParams(0,dp(36),1f).apply{
                setMargins(dp(1),0,dp(1),0)
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
        AirAlerts.initialize(this)

        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor)
            setPadding(dp(8),dp(4),dp(8),dp(5))
            setOnApplyWindowInsetsListener{v,insets->
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout())
                v.setPadding(bars.left+dp(8),bars.top+dp(4),
                    bars.right+dp(8),bars.bottom+dp(5))
                insets
            }
        }

        val title=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        title.addView(label("Навигатор · OBD GPS",17f).apply {
            typeface=Typeface.DEFAULT_BOLD
            isSingleLine=true
            ellipsize=TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        alertsView=AirAlertIndicatorsView(this)
        title.addView(alertsView,LinearLayout.LayoutParams(
            dp(168),dp(53)))
        root.addView(title)
        updateAlertIndicator()

        source=label("Источники координат: ожидание",12f,secondary)
        source.setPadding(dp(2),dp(1),dp(2),dp(2))
        source.maxLines=1
        source.ellipsize=android.text.TextUtils.TruncateAt.END
        root.addView(source)

        pickStatus=label("Коснись карты, чтобы выбрать пункт назначения",13f,secondary)
        pickStatus.setPadding(dp(2),dp(1),dp(2),dp(2))
        pickStatus.maxLines=2
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
        zoom.addView(mapControl("+"){map.changeZoom(1)},
            LinearLayout.LayoutParams(dp(45),dp(45)))
        zoom.addView(mapControl("−"){map.changeZoom(-1)},
            LinearLayout.LayoutParams(dp(45),dp(45)).apply{topMargin=dp(4)})
        zoom.addView(mapControl("◎"){map.followCar()},
            LinearLayout.LayoutParams(dp(45),dp(45)).apply{topMargin=dp(4)})
        mapContainer.addView(zoom,FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.RIGHT or Gravity.TOP).apply{setMargins(0,dp(8),dp(7),0)})
        root.addView(mapContainer,LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,0,1f))

        // Status overlays the bottom of the map instead of taking up a
        // separate full-height block. This leaves more vertical room for roads.
        val mapStatus=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(7),dp(10),dp(8))
            background=GradientDrawable().apply {
                setColor(Color.argb(228,27,31,38))
                cornerRadius=dp(11).toFloat()
            }
        }
        summary=label("Маршрут ещё не построен",13f,secondary).apply {
            maxLines=2
        }
        mapStatus.addView(summary)
        instruction=label("Выбери цель и нажми «Маршрут»",16f,textColor).apply {
            typeface=Typeface.DEFAULT_BOLD
            maxLines=2
            ellipsize=android.text.TextUtils.TruncateAt.END
        }
        mapStatus.addView(instruction)
        mapContainer.addView(mapStatus,FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply {
                setMargins(dp(5),0,dp(5),dp(5))
            })

        root.addView(row(
            styledButton("Маршрут"){buildRoute(false)},
            styledButton("Начать ведение"){startGuidance()},
            styledButton("Сброс"){stopGuidance()}
        ))
        voiceButton=styledButton("Голос: выкл"){
            voiceEnabled=!voiceEnabled
            voiceButton.text=if(voiceEnabled)"Голос: вкл" else "Голос: выкл"
            if(!voiceEnabled)tts?.stop()
        }
        root.addView(row(
            styledButton("Ведение по датчикам"){sendService(NavService.START_DR)},
            styledButton("Вернуться к GPS"){sendService(NavService.STOP_DR)},
            voiceButton
        ))
        // Attribution is kept on the map itself for OSM license compliance;
        // the former full-width copyright footer no longer consumes map height.
        val credit=TextView(this).apply {
            text="© OpenStreetMap contributors"
            textSize=10f
            setTextColor(Color.rgb(234,240,248))
            setPadding(dp(5),dp(2),dp(5),dp(2))
            background=GradientDrawable().apply {
                setColor(Color.argb(205,27,31,38))
                cornerRadius=dp(4).toFloat()
            }
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.openstreetmap.org/copyright")))
            }
        }
        mapContainer.addView(credit,FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.LEFT).apply{
                setMargins(dp(5),dp(5),0,0)
            })
        mapContainer.addView(mapControl("↓") {
            startActivity(Intent(this,OfflineMapsActivity::class.java))
        }.apply{
            textSize=23f
            contentDescription="Сохранённые офлайн-карты и импорт области"
        },FrameLayout.LayoutParams(dp(44),dp(44),
            Gravity.TOP or Gravity.LEFT).apply{
            setMargins(dp(7),dp(32),0,0)
        })
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

    private fun selectFoundAddress(item:PlaceSearch.Result) {
        val p=item.coordinate
        if(!RoutingEngine.valid(p))return
        val label=PlaceSearch.cyrillicLabel(item.label)
        NavigatorPrefs(this).rememberAddress(
            NavigatorPrefs.RecentAddress(label,p.lat,p.lon))
        selectedDestination=p
        State.navigationDestination=p.lat to p.lon
        map.centerAt(p.lat,p.lon)
        pickMode="DEST"
        pickStatus.text="Цель: "+label
        buildRoute(false)
    }

    private fun performAddressSearch(raw:String) {
        val query=raw.trim()
        if(query.length<3 || !PlaceSearch.isAllowedAddress(query)) {
            speechInput?.error="Введи название улицы или адрес русскими/украинскими буквами"
            pickStatus.text="Поиск: только адреса на русском или украинском"
            return
        }
        addressSearchDialog?.dismiss()
        pickStatus.text="Поиск адреса: "+query
        placeSearch.search(query){result->
            if(isFinishing || isDestroyed)return@search
            result.fold(
                onSuccess={items->
                    if(items.isEmpty()) {
                        pickStatus.text="Ничего не найдено: "+query
                        AlertDialog.Builder(this)
                            .setTitle("Адрес не найден")
                            .setMessage("Уточни город, улицу и номер дома.")
                            .setPositiveButton("Повторить"){_,_->showAddressSearch(query)}
                            .setNegativeButton("Закрыть",null).show()
                    } else {
                        AlertDialog.Builder(this).setTitle("Выбери адрес")
                            .setItems(items.map {
                                PlaceSearch.cyrillicLabel(it.label)
                            }.toTypedArray()){_,index->
                                selectFoundAddress(items[index])
                            }
                            .setNegativeButton("Отмена",null).show()
                    }
                },
                onFailure={e->
                    val detail=e.localizedMessage ?: "Нет ответа от сервиса адресов"
                    pickStatus.text="Ошибка поиска: "+detail
                    AlertDialog.Builder(this).setTitle("Поиск адреса")
                        .setMessage(detail+"\n\nПроверь интернет или выбери точку на карте.")
                        .setPositiveButton("Повторить"){_,_->showAddressSearch(query)}
                        .setNegativeButton("Закрыть",null).show()
                }
            )
        }
    }

    /** Uses the system speech-recognition activity, no microphone permission
     * in this app is needed; a speech recognition provider must be installed. */
    private fun askSpeechLanguage() {
        AlertDialog.Builder(this)
            .setTitle("Голосовой поиск адреса")
            .setItems(arrayOf("Русский","Українська")) { _,index ->
                val language=if(index==0)"ru-RU" else "uk-UA"
                val request=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE,language)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,language)
                    putExtra(RecognizerIntent.EXTRA_PROMPT,
                        "Назови город, улицу и номер дома")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(request,speechRequestCode)
                } catch (_:ActivityNotFoundException) {
                    AlertDialog.Builder(this)
                        .setTitle("Голосовой поиск недоступен")
                        .setMessage("На телефоне нет приложения распознавания речи. " +
                            "Установи или включи голосовой ввод Android.")
                        .setPositiveButton("Понятно",null).show()
                } catch (e:Exception) {
                    pickStatus.text="Ошибка запуска голосового поиска: "+
                        (e.localizedMessage?:"сервис недоступен")
                }
            }.show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=speechRequestCode || resultCode!=Activity.RESULT_OK)return
        val choices=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val text=choices.map{PlaceSearch.cyrillicLabel(it)}
            .firstOrNull{PlaceSearch.isAllowedAddress(it) && it.length>=3}
        if(text==null) {
            speechInput?.error="Не распознан адрес на русском или украинском"
            pickStatus.text="Не удалось распознать адрес. Повтори голосовой поиск."
            return
        }
        speechInput?.setText(text)
        performAddressSearch(text)
    }

    private fun showAddressSearch(prefill:String="") {
        val field=EditText(this).apply {
            hint="Город, улица, номер дома"
            isSingleLine=true
            inputType=InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            textSize=15f
            setTextColor(textColor)
            setHintTextColor(secondary)
            setText(prefill)
            setSelection(text.length)
            // Exclude Latin, coordinates and unsupported scripts from typing
            // or paste; permit Arabic numerals only for house numbering.
            filters=arrayOf(InputFilter { source,start,end,_,_,_ ->
                val allowed=buildString {
                    for(i in start until end) {
                        val ch=source[i]
                        if(ch in 'А'..'я' || ch in "ЁёІіЇїЄєҐґ" ||
                            ch.isDigit() || ch.isWhitespace() ||
                            ch in " ,.-/№'’()")append(ch)
                    }
                }
                if(allowed.length==end-start)null else allowed
            })
            setOnEditorActionListener { _,actionId,_->
                if(actionId==EditorInfo.IME_ACTION_SEARCH) {
                    performAddressSearch(text.toString())
                    true
                } else false
            }
        }
        speechInput=field
        val mic=mapControl("🎤") { askSpeechLanguage() }.apply {
            contentDescription="Голосовой поиск адреса"
            textSize=21f
        }
        val top=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            addView(field,LinearLayout.LayoutParams(0,dp(48),1f))
            addView(mic,LinearLayout.LayoutParams(dp(50),dp(48)).apply{
                leftMargin=dp(6)
            })
        }
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),0,dp(18),dp(7))
            addView(top)
        }
        val history=NavigatorPrefs(this).recentAddresses()
        content.addView(TextView(this).apply {
            text="Последние адреса"
            textSize=14f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(142,195,251))
            setPadding(dp(2),dp(14),0,dp(7))
        })
        if(history.isEmpty()) {
            content.addView(TextView(this).apply {
                text="Пока нет сохранённых адресов"
                textSize=13f
                setTextColor(secondary)
                setPadding(dp(4),dp(6),0,dp(12))
            })
        } else {
            history.take(5).forEachIndexed{index,recent->
                val row=TextView(this).apply {
                    text=(index+1).toString()+". "+
                        PlaceSearch.cyrillicLabel(recent.label)
                    textSize=14f
                    setTextColor(textColor)
                    maxLines=2
                    setPadding(dp(6),dp(11),dp(6),dp(11))
                    setOnClickListener {
                        addressSearchDialog?.dismiss()
                        selectFoundAddress(PlaceSearch.Result(
                            recent.label,
                            RoutingEngine.Point(recent.lat,recent.lon)))
                    }
                }
                content.addView(row,LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
                content.addView(View(this).apply {
                    setBackgroundColor(Color.rgb(57,68,82))
                },LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,dp(1)))
            }
        }
        val scroll=ScrollView(this).apply {
            addView(content)
            isFillViewport=false
        }
        val dlg=AlertDialog.Builder(this).setTitle("Поиск адреса")
            .setView(scroll)
            .setPositiveButton("Найти",null)
            .setNegativeButton("Закрыть",null)
            .create()
        addressSearchDialog=dlg
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                performAddressSearch(field.text.toString())
            }
        }
        dlg.setOnDismissListener {
            if(addressSearchDialog===dlg)addressSearchDialog=null
            if(speechInput===field)speechInput=null
        }
        dlg.show()
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

    /** Direction of the road ahead from the nearest route segment. */
    private fun upcomingCourse(route:RoutingEngine.Route,nearestIndex:Int):Double? {
        val p=route.points
        if(p.size<2)return null
        val fromIndex=nearestIndex.coerceIn(0,p.lastIndex-1)
        val from=p[fromIndex]
        var toIndex=fromIndex+1
        var traveled=0.0
        while(toIndex<p.lastIndex && traveled<75.0 &&
            toIndex-fromIndex<120) {
            traveled+=RoutingEngine.meters(p[toIndex-1],p[toIndex])
            if(traveled>=75.0)break
            toIndex++
        }
        val end=p[toIndex]
        val latitude=Math.toRadians((from.lat+end.lat)/2.0)
        val east=(end.lon-from.lon)*cos(latitude)
        val north=end.lat-from.lat
        if(hypot(east,north)<1e-10)return null
        return (Math.toDegrees(atan2(east,north))+360.0)%360.0
    }

    private fun startGuidance() {
        val route=plan
        if(route==null) {
            buildRoute(false)
            summary.text="Построй маршрут, затем начни ведение"
            return
        }
        val fix=currentFix()
        val userPoint=fix?.let{RoutingEngine.Point(it.lat,it.lon)}
        val nearest=userPoint?.let{route.progress(it).nearestShapeIndex}?:0
        val ahead=upcomingCourse(route,nearest)
        val fallback=userPoint?:manualOrigin?:route.points.firstOrNull()
        // Selecting the destination pans the map: a real Start press must
        // restore car-following and face down the planned route immediately.
        map.beginRouteGuidance(ahead,fallback)
        active=true
        State.activeRouting=true
        lastVoiceKey=""
        pickStatus.text=if(fix==null)
            "Маршрут впереди. Нет свежих координат — показан старт маршрута"
        else "Ведение: слежение за автомобилем и направлением маршрута"
        speak("Ведение по маршруту начато")
    }

    private fun stopGuidance() {
        active=false
        State.activeRouting=false
        map.stopRouteGuidance()
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
                map.stopRouteGuidance()
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
            AirAlerts.refreshIfDue()
            updateAlertIndicator()
            val fix=currentFix()
            val route=plan
            val speed=liveCarSpeed()
            val routeProgress=if(route!=null && fix!=null)
                route.progress(RoutingEngine.Point(fix.lat,fix.lon)) else null
            val routeDirection=route?.let {
                upcomingCourse(it,routeProgress?.nearestShapeIndex?:0)
            }
            map.updateFromNavigation(fix,route?.points?:emptyList(),
                selectedDestination,manualOrigin,speed,routeDirection,active)
            source.text=State.sourceDescription+" · "+
                if(State.dr)"DR + OBD" else "обычное местоположение"
            if(route!=null) {
                if(fix!=null) {
                    val progress=routeProgress
                        ?:route.progress(RoutingEngine.Point(fix.lat,fix.lon))
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
