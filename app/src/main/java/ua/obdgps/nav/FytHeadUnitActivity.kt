package ua.obdgps.nav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.util.Locale

/**
 * AOSP-first FYT UIS7870 dashboard.
 * Designed for fixed 2000x1200 landscape / Android 13, with dp-aware sizing.
 * Startup never requires a GPS fix, Bluetooth, Google Play or a downloaded map.
 */
class FytHeadUnitActivity:Activity(){
    private val background=Color.rgb(17,26,37)
    private val panel=Color.rgb(31,43,58)
    private val bright=Color.rgb(239,246,252)
    private val secondary=Color.rgb(168,190,210)
    private val blue=Color.rgb(94,181,246)
    private val green=Color.rgb(72,204,142)
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var gyroButton:Button
    private lateinit var accelButton:Button
    private lateinit var sensorHelp:TextView
    private lateinit var gpsText:TextView
    private lateinit var obdText:TextView
    private lateinit var infoText:TextView
    private lateinit var monitorButton:Button
    private var alerts:AirAlertIndicatorsView?=null
    private var permissionRequestPending=false
    private var lastMonitorStart=0L
    private var selectedSensor="gyro"
    private var adapterDialog:AlertDialog?=null
    private var adapterRows:ArrayAdapter<String>?=null
    private var adapterChoices:List<String> = emptyList()

    private fun dp(x:Int)=(x*resources.displayMetrics.density+.5f).toInt()
    private fun shape(fill:Int=panel,border:Int=Color.rgb(59,78,99)):GradientDrawable=
        GradientDrawable().apply{
            cornerRadius=dp(12).toFloat()
            setColor(fill)
            setStroke(dp(1),border)
        }
    private fun text(value:String,size:Float=14f,bold:Boolean=false):TextView=
        TextView(this).apply{
            this.text=value
            textSize=size
            setTextColor(bright)
            gravity=Gravity.CENTER_VERTICAL
            includeFontPadding=true
            if(bold)typeface=Typeface.DEFAULT_BOLD
        }
    private fun button(value:String,click:()->Unit):Button=Button(this).apply{
        this.text=value
        isAllCaps=false
        textSize=15f
        maxLines=3
        minHeight=0
        minimumHeight=0
        minWidth=0
        minimumWidth=0
        gravity=Gravity.CENTER
        setTextColor(bright)
        background=shape()
        setPadding(dp(7),dp(3),dp(7),dp(3))
        setOnClickListener{
            try{click()}catch(e:Throwable){
                FytCrashReporter.record(this@FytHeadUnitActivity,
                    "Нажатие: "+value,e)
                Toast.makeText(this@FytHeadUnitActivity,
                    "Ошибка: открой Диагностику",Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun twin(parent:LinearLayout,heightDp:Int,first:Button,second:Button){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        for(b in listOf(first,second)){
            row.addView(b,LinearLayout.LayoutParams(0,dp(heightDp),1f).apply{
                setMargins(dp(3),dp(3),dp(3),dp(3))
            })
        }
        parent.addView(row)
    }
    private fun title(section:String):TextView=text(section,13f,true).apply{
        setTextColor(blue)
        setPadding(dp(7),dp(10),0,dp(5))
    }
    private fun labelPanel(parent:LinearLayout,value:String):TextView{
        val t=text(value,14f)
        t.background=shape()
        t.setPadding(dp(12),dp(10),dp(11),dp(10))
        parent.addView(t,LinearLayout.LayoutParams(-1,-2).apply{
            setMargins(dp(3),dp(2),dp(3),dp(6))
        })
        return t
    }

    private fun hasLocation()=
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
            PackageManager.PERMISSION_GRANTED

    private fun hasBluetooth()=Build.VERSION.SDK_INT<31 ||
        (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==
            PackageManager.PERMISSION_GRANTED &&
         checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==
            PackageManager.PERMISSION_GRANTED)

    private fun requestPermissions(){
        if(permissionRequestPending)return
        val missing=ArrayList<String>()
        if(!hasLocation()){
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if(Build.VERSION.SDK_INT>=31){
            for(p in listOf(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT)){
                if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)
                    missing.add(p)
            }
        }
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=
                PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if(missing.isEmpty()){
            Toast.makeText(this,"Все необходимые разрешения уже выданы",
                Toast.LENGTH_SHORT).show()
            return
        }
        permissionRequestPending=true
        requestPermissions(missing.toTypedArray(),78)
    }

    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(
        requestCode:Int,permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,results)
        if(requestCode==78){
            permissionRequestPending=false
            if(hasLocation() && monitoringEnabled())startMonitor(force=true)
            else Toast.makeText(this,
                "Без геолокации карта доступна, но отслеживание GPS выключено",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun monitoringEnabled()=
        getSharedPreferences("fyt_unit",MODE_PRIVATE)
            .getBoolean("monitor_enabled",false)

    private fun startMonitor(force:Boolean=false){
        if(!hasLocation()){
            requestPermissions()
            return
        }
        val now=SystemClock.elapsedRealtime()
        if(!force && lastMonitorStart>0 && now-lastMonitorStart<3000L)return
        lastMonitorStart=now
        try {
            startForegroundService(Intent(this,NavService::class.java)
                .setAction(NavService.MONITOR))
        }catch(e:Throwable){
            FytCrashReporter.record(this,"Запуск мониторинга",e)
            Toast.makeText(this,"Мониторинг не запустился: открой Диагностику",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun enableMonitoring(){
        getSharedPreferences("fyt_unit",MODE_PRIVATE).edit()
            .putBoolean("monitor_enabled",true)
            .putBoolean("auto_monitor",true).apply()
        startMonitor(force=true)
    }
    private fun disableMonitoring(){
        getSharedPreferences("fyt_unit",MODE_PRIVATE).edit()
            .putBoolean("monitor_enabled",false)
            .putBoolean("auto_monitor",false).apply()
        try{
            startService(Intent(this,NavService::class.java)
                .setAction(NavService.EXIT))
        }catch(_:Exception){}
    }
    private fun send(action:String,address:String?=null){
        if(!hasLocation()){
            requestPermissions()
            return
        }
        val i=Intent(this,NavService::class.java).setAction(action)
        if(address!=null)i.putExtra("address",address)
        try{startForegroundService(i)}
        catch(e:Throwable){
            FytCrashReporter.record(this,"Команда: "+action,e)
            Toast.makeText(this,"Ошибка команды, см. Диагностику",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openVector(){
        try{
            // No downloaded map is normal on a brand-new FYT installation.
            if(OfflineRegionPacks.mapFiles(this).isEmpty()){
                AlertDialog.Builder(this)
                    .setTitle("Нет загруженной офлайн-карты")
                    .setMessage("Перед первым использованием скачай карту Украины. "+
                        "Главный экран работает даже без неё.")
                    .setPositiveButton("Офлайн-карты"){_,_->
                        startActivity(Intent(this,OfflineMapsActivity::class.java))
                    }.setNegativeButton("Позже",null).show()
            }else startActivity(Intent(this,
                OfflineVectorNavigatorActivity::class.java))
        }catch(e:Throwable){
            FytCrashReporter.record(this,"Открытие карты",e)
            showDiagnostics()
        }
    }
    private fun openStandard(){
        try{startActivity(Intent(this,NavigatorActivity::class.java))}
        catch(e:Throwable){
            FytCrashReporter.record(this,"Открытие навигатора",e)
            showDiagnostics()
        }
    }
    private fun calibrate(which:String){
        selectedSensor=which
        val name=if(which=="gyro")"гироскоп" else "акселерометр"
        AlertDialog.Builder(this)
            .setTitle("Калибровка: "+name)
            .setMessage("Калибровка выполняется совместно для двух датчиков "+
                "на неподвижно закреплённой магнитоле.\n\n"+
                "1. Автомобиль стоит, Vgate показывает 0 км/ч.\n"+
                "2. Сохраняем показания 5 секунд.\n"+
                "3. Нужно ≥40 отсчётов гироскопа, ≥20 отсчётов "+
                "ускорения и допустимый шум.\n\n"+
                "Статус: "+State.calibrationText)
            .setPositiveButton("Начать 5 с"){_,_->send(NavService.CALIBRATE)}
            .setNegativeButton("Закрыть",null).show()
    }
    private fun selectVgate(){
        if(!hasBluetooth()){
            requestPermissions()
            Toast.makeText(this,"Для Vgate нужен Bluetooth",
                Toast.LENGTH_SHORT).show()
            return
        }
        send(NavService.SCAN)
        val rows=ArrayAdapter(this,android.R.layout.simple_list_item_1,
            mutableListOf("Поиск сопряжённых и BLE адаптеров…"))
        adapterRows=rows
        adapterDialog=AlertDialog.Builder(this)
            .setTitle("Выбери Vgate / ELM327")
            .setAdapter(rows){_,i->
                adapterChoices.getOrNull(i)?.let{send(NavService.CONNECT,it)}
            }
            .setPositiveButton("Сканировать ещё раз"){_,_->selectVgate()}
            .setNegativeButton("Закрыть",null).create()
        adapterDialog?.setOnDismissListener{
            adapterDialog=null;adapterRows=null
        }
        adapterDialog?.show()
    }

    private fun selectProtocol(){
        val names=arrayOf("AUTO / все протоколы","CAN ISO 15765","K-Line",
            "MUT-II (эксперимент)")
        val actions=arrayOf(NavService.SET_AUTO,NavService.SET_CAN,
            NavService.SET_OBD,NavService.SET_MUT)
        AlertDialog.Builder(this).setTitle("Протокол Vgate")
            .setItems(names){_,i->send(actions[i])}
            .setNegativeButton("Отмена",null).show()
    }
    private fun showDiagnostics(){
        val report=FytCrashReporter.latest(this)
            ?: "Сбоев приложения пока не зарегистрировано.\n"+
                "Android "+Build.VERSION.RELEASE+" · "+Build.DISPLAY
        FytCrashReporter.acknowledge(this)
        AlertDialog.Builder(this)
            .setTitle("Диагностика FYT 7870")
            .setMessage(report)
            .setPositiveButton("Закрыть",null)
            .setNeutralButton("Копировать"){_,_->
                val clipboard=getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("FYT 7870 crash report",report))
                Toast.makeText(this,"Отчёт скопирован",Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Поделиться"){_,_->
                val share=Intent(Intent.ACTION_SEND).apply{
                    type="text/plain"
                    putExtra(Intent.EXTRA_TEXT,report)
                }
                startActivity(Intent.createChooser(share,"Отправить отчёт"))
            }.show()
    }

    private fun showLearningProfile(){
        // Read stored weights/counters even before the service starts.
        // Training examples belong to this FYT package, not to the phone.
        val model=AdaptiveDrLearning(this)
        AlertDialog.Builder(this)
            .setTitle("ИИ · обучение GPS / DR")
            .setMessage(model.status()+"\n\n"+
                model.detail()+"\n\n"+
                "Для набора примеров открой «ТЕСТ GPS / DR», подключи "+
                "Vgate и начни запись двух треков. "+
                "Внешний GM906BT должен передавать проверенные координаты "+
                "через другое приложение. ИИ обучается только после "+
                "принятия достоверных GPS-позиций.")
            .setPositiveButton("Открыть тест"){_,_->
                startActivity(Intent(this,InertialMapActivity::class.java))
            }.setNegativeButton("Закрыть",null).show()
    }

    private fun render(){
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(this@FytHeadUnitActivity.background)
            setPadding(dp(12),dp(5),dp(12),dp(8))
            setOnApplyWindowInsetsListener{v,insets->
                val edges=insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(dp(12)+edges.left,dp(5)+edges.top,
                    dp(12)+edges.right,dp(8)+edges.bottom)
                insets
            }
        }
        setContentView(root)
        // FYT 2000x1200: no decorative title row or route-preview pane.
        // Only a narrow navigation rail at left; the data dashboard gets
        // the remaining width and scales with the unit's configured density.
        val screenDp=resources.configuration.screenWidthDp
        val leftDp=(screenDp*.18f).toInt().coerceIn(180,240)
            .coerceAtMost((screenDp*.34f).toInt())
        val main=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        root.addView(main,LinearLayout.LayoutParams(-1,0,1f))
        val railScroll=ScrollView(this).apply{
            isFillViewport=true
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        main.addView(railScroll,LinearLayout.LayoutParams(dp(leftDp),-1))
        val rail=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            background=shape(Color.rgb(25,36,50))
            setPadding(dp(6),dp(8),dp(6),dp(8))
        }
        railScroll.addView(rail)
        fun railButton(label:String,height:Int=59,click:()->Unit){
            rail.addView(button(label,click).apply{
                textSize=14f
                maxLines=2
            },LinearLayout.LayoutParams(-1,dp(height)).apply{
                setMargins(dp(1),dp(3),dp(1),dp(3))
            })
        }
        railButton("ВЕКТОРНАЯ\nКАРТА"){openVector()}
        railButton("ПРОДОЛЖИТЬ\nМАРШРУТ"){openVector()}
        railButton("НАВИГАТОР"){openStandard()}
        railButton("ТЕСТ GPS / DR"){startActivity(Intent(this,
            InertialMapActivity::class.java))}
        railButton("ОФЛАЙН-КАРТЫ"){startActivity(Intent(this,
            OfflineMapsActivity::class.java))}
        railButton("ПРОФИЛЬ ИИ"){showLearningProfile()}
        val scroll=ScrollView(this).apply{
            isFillViewport=false
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background=shape(Color.rgb(24,35,49))
        }
        main.addView(scroll,LinearLayout.LayoutParams(0,-1,1f).apply{
            leftMargin=dp(7)
        })
        val side=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(9),dp(4),dp(9),dp(9))
        }
        scroll.addView(side)
        side.addView(title("ИНЕРЦИАЛЬНЫЕ ДАТЧИКИ"))
        gyroButton=button("ГИРОСКОП"){calibrate("gyro")}
        accelButton=button("АКСЕЛЕРОМЕТР"){calibrate("accel")}
        twin(side,84,gyroButton,accelButton)
        sensorHelp=text("Калибровка: ожидаю данные Vgate и датчиков",12.5f)
            .apply{setTextColor(secondary);setPadding(dp(10),dp(7),dp(10),dp(11))}
        side.addView(sensorHelp)
        side.addView(title("КООРДИНАТЫ И ИСТОЧНИКИ"))
        gpsText=labelPanel(side,"USB GM906BT: нет данных\nВстроенный GPS: нет данных")
        side.addView(title("VGATE / ELM327"))
        obdText=labelPanel(side,"OBD: нет подключения")
        twin(side,60,button("ПОДКЛЮЧИТЬ\nVGATE"){selectVgate()},
            button("ПРОТОКОЛ"){selectProtocol()})
        side.addView(title("РЕЖИМ НАВИГАЦИИ"))
        twin(side,62,button("ВЕДЕНИЕ ПО\nДАТЧИКАМ"){send(NavService.START_DR)},
            button("ВЕРНУТЬ GPS"){send(NavService.STOP_DR)})
        monitorButton=button("ВКЛЮЧИТЬ МОНИТОРИНГ"){
            if(monitoringEnabled())disableMonitoring() else enableMonitoring()
        }
        twin(side,59,monitorButton,
            button("РАЗРЕШЕНИЯ"){requestPermissions()})
        side.addView(title("СОСТОЯНИЕ / ДИАГНОСТИКА"))
        infoText=labelPanel(side,"Запуск в безопасном режиме. "+
            "Данные GPS не изменяются.")
        twin(side,53,
            button("ДИАГНОСТИКА"){showDiagnostics()},
            button("ОБНОВИТЬ"){updateView()})
    }

    private fun updateView(){
        if(!::gyroButton.isInitialized)return
        val now=SystemClock.elapsedRealtime()
        val gyroFresh=State.gyroSampleMs>0L &&
            now-State.gyroSampleMs in 0L..4200L
        val accelFresh=State.accelSampleMs>0L &&
            now-State.accelSampleMs in 0L..4200L
        val bothCalibrated=State.gyroCalibrated && State.accelCalibrated &&
            !State.stationaryCalibrationRunning
        val gyroCheck=gyroFresh && bothCalibrated
        val accelCheck=accelFresh && bothCalibrated
        gyroButton.text="ГИРОСКОП "+(if(gyroCheck)"✓" else "")+"\n"+
            (if(gyroFresh)String.format(Locale.US,"%.1f °/с",
                State.yawDegPerSec) else "Нет сигнала")
        accelButton.text="АКСЕЛЕРОМЕТР "+
            (if(accelCheck)"✓" else "")+"\n"+
            (if(accelFresh && State.linearAccelerationMps2.isFinite())
                String.format(Locale.US,"%.2f м/с²",
                    State.linearAccelerationMps2) else "Нет сигнала")
        (gyroButton.background as? GradientDrawable)?.setStroke(
            dp(if(selectedSensor=="gyro")2 else 1),
            if(gyroCheck)green else blue)
        (accelButton.background as? GradientDrawable)?.setStroke(
            dp(if(selectedSensor=="accel")2 else 1),
            if(accelCheck)green else blue)
        sensorHelp.text=when{
            State.stationaryCalibrationRunning->
                "Идёт калибровка (5 секунд): машина неподвижна, OBD = 0 км/ч."
            bothCalibrated->"✓ Смещения сохранены. Если пропадёт поток "+
                "данных датчика, его зелёная галочка исчезнет."
            else->"Нужна стоянка: 0 км/ч по Vgate, 5 секунд. "+
                "≥40 измерений гироскопа и ≥20 акселерометра.\n"+
                State.calibrationText
        }
        val ext=State.gpsExternalUpdateMs>0L &&
            now-State.gpsExternalUpdateMs in 0L..8500L
        val builtIn=State.gpsInternalUpdateMs>0L &&
            now-State.gpsInternalUpdateMs in 0L..8500L
        gpsText.text="USB GM906BT  "+(if(ext)"● ЕСТЬ СИГНАЛ"
            else "○ ожидание")+"\n"+
            "Встроенный GNSS  "+(if(builtIn)"● ЕСТЬ СИГНАЛ"
            else "○ ожидание")+"\n"+
            "Источник: "+State.gpsActiveSource+"\n"+
            State.sourceDescription
        val obdNow=State.speedUpdatedElapsedMs>0L &&
            now-State.speedUpdatedElapsedMs in 0L..4500L
        obdText.text="Адаптер: "+State.deviceName+"\n"+
            "Скорость: "+(if(obdNow)String.format(Locale.US,"%.0f км/ч",
                State.obdSpeedKmh()?:0.0) else "нет свежих данных")+"\n"+
            "Режим: "+State.modeLabel
        val session=FytRouteStore.load(this)
        monitorButton.text=if(monitoringEnabled())"ОСТАНОВИТЬ\nМОНИТОРИНГ"
            else "ВКЛЮЧИТЬ\nМОНИТОРИНГ"
        infoText.text="GPS: "+(if(hasLocation())"разрешён" else "нет разрешения")+
            "  ·  Bluetooth: "+(if(hasBluetooth())"разрешён" else "не разрешён")+
            "\nМаршрут: "+when{
                session?.active==true->"ведение сохранено"
                session!=null->"цель сохранена"
                else->"не выбран"
            }+"\nПробуждение: "+State.gpsWakeStatus+
            "\n"+(if(FytCrashReporter.unread(this))
                "⚠ Ошибка предыдущего запуска. Открой Диагностику."
                else State.message.take(250))
        adapterDialog?.let{ dialog->
            if(dialog.isShowing){
                val choices=State.devices.toList()
                if(choices.map{it.first}!=adapterChoices){
                    adapterChoices=choices.map{it.first}
                    val display=if(choices.isEmpty())
                        listOf("Ищу Vgate…") else
                        choices.map{it.second}
                    adapterRows?.clear()
                    adapterRows?.addAll(display)
                    adapterRows?.notifyDataSetChanged()
                }
            }
        }
        try{alerts?.refresh()}catch(_:Throwable){}
    }
    private val refresh=object:Runnable{
        override fun run(){
            try{updateView()}catch(e:Throwable){
                FytCrashReporter.record(this@FytHeadUnitActivity,
                    "Обновление панели",e)
            }
            handler.postDelayed(this,1100L)
        }
    }
    private fun failSafeUi(error:Throwable){
        FytCrashReporter.record(this,"Отрисовка первого экрана",error)
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(this@FytHeadUnitActivity.background)
            setPadding(dp(35),dp(30),dp(35),dp(25))
        }
        box.addView(text("FYT 7870 · Безопасный режим",25f,true))
        box.addView(text("Основной интерфейс столкнулся с ошибкой. "+
            "Отчёт сохранён. Можно открыть диагностику или повторить запуск.",16f))
        box.addView(button("ПОКАЗАТЬ И СКОПИРОВАТЬ ОШИБКУ"){showDiagnostics()},
            LinearLayout.LayoutParams(-1,dp(76)))
        box.addView(button("ПОВТОРИТЬ ЗАПУСК"){recreate()},
            LinearLayout.LayoutParams(-1,dp(76)))
        setContentView(box)
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        // No service starts or permission dialogs during cold startup.
        try{render()}catch(e:Throwable){failSafeUi(e)}
    }
    override fun onResume(){
        super.onResume()
        // Monitoring restarts after FYT ACC wake ONLY after user enabled it.
        if(monitoringEnabled() && hasLocation())startMonitor()
        handler.removeCallbacks(refresh)
        if(::gyroButton.isInitialized)handler.post(refresh)
    }
    override fun onPause(){
        handler.removeCallbacks(refresh)
        adapterDialog?.dismiss()
        super.onPause()
    }
    override fun onDestroy(){
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }
}
