package ua.obdgps.nav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import java.util.Locale

/**
 * Dedicated 9-inch landscape dashboard for FYT UIS7870.
 * Only two real motion sensors: gyro + accelerometer; no compass UI.
 * GM906BT mock output is INPUT ONLY. No mock provider is set by this app.
 */
class FytHeadUnitActivity:Activity(){
    private val bg=Color.rgb(20,27,35)
    private val panel=Color.rgb(37,48,61)
    private val fg=Color.rgb(237,245,252)
    private val muted=Color.rgb(176,196,214)
    private val sky=Color.rgb(105,192,255)
    private val tick=Handler(Looper.getMainLooper())
    private lateinit var alerts:AirAlertIndicatorsView
    private lateinit var gyroButton:Button
    private lateinit var accelButton:Button
    private lateinit var sensorHelp:TextView
    private lateinit var gpsText:TextView
    private lateinit var obdText:TextView
    private lateinit var infoText:TextView
    private var selectedSensor="gyro"
    private var permissionRequested=false
    private var dialogAdapter:ArrayAdapter<String>?=null
    private var dialogKeys=emptyList<String>()
    private var adapterDialog:AlertDialog?=null

    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun bgDrawable(color:Int=panel,stroke:Int=Color.rgb(63,79,94))=
        GradientDrawable().apply{cornerRadius=dp(11).toFloat();setColor(color)
            setStroke(dp(1),stroke)}
    private fun label(value:String,size:Float=15f,bold:Boolean=false)=
        TextView(this).apply{
            text=value;textSize=size;setTextColor(fg)
            if(bold)typeface=Typeface.DEFAULT_BOLD
        }
    private fun action(name:String,callback:()->Unit)=Button(this).apply{
        text=name
        isAllCaps=false
        textSize=16f
        maxLines=2
        gravity=Gravity.CENTER
        minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0
        setTextColor(fg)
        background=bgDrawable()
        setPadding(dp(5),dp(3),dp(5),dp(3))
        setOnClickListener{callback()}
    }
    private fun buttonRow(parent:LinearLayout,height:Int,vararg views:Button){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        for(v in views){
            row.addView(v,LinearLayout.LayoutParams(0,dp(height),1f).apply{
                setMargins(dp(4),dp(3),dp(4),dp(3))
            })
        }
        parent.addView(row)
    }

    private fun launchService(action:String,address:String?=null){
        if(!hasPermissions()){
            askPermissions()
            Toast.makeText(this,"Сначала разреши GPS и Bluetooth",Toast.LENGTH_SHORT).show()
            return
        }
        try{
            startForegroundService(Intent(this,NavService::class.java).setAction(action)
                .apply{if(address!=null)putExtra("address",address)})
        }catch(e:Exception){
            Toast.makeText(this,"Мониторинг: ${e.localizedMessage}",Toast.LENGTH_LONG).show()
        }
    }
    private fun hasPermissions()=arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
        .all{checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}

    private fun askPermissions(){
        if(permissionRequested || hasPermissions())return
        permissionRequested=true
        val missing=ArrayList<String>()
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
            PackageManager.PERMISSION_GRANTED){
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        for(p in arrayOf(Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS)){
            if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p)
        }
        if(missing.isNotEmpty())requestPermissions(missing.toTypedArray(),70)
    }
    override fun onRequestPermissionsResult(
        requestCode:Int,permissions:Array<out String>,grantResults:IntArray
    ){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==70 && hasPermissions())launchService(NavService.MONITOR)
    }

    private val uiUpdate=object:Runnable{
        override fun run(){
            if(!::gyroButton.isInitialized)return
            alerts.refresh()
            val now=SystemClock.elapsedRealtime()
            val gyroFresh=State.gyroSampleMs>0 && now-State.gyroSampleMs<4000L
            val accelFresh=State.accelSampleMs>0 && now-State.accelSampleMs<4000L
            val gyroReady=State.gyroCalibrated && State.accelCalibrated &&
                !State.stationaryCalibrationRunning && gyroFresh
            val accelReady=gyroReady && accelFresh
            gyroButton.text="ГИРОСКОП ${if(gyroReady)"✓" else ""}\n"+
                (if(gyroFresh)String.format(Locale.US,"%.1f °/с",State.yawDegPerSec)
                    else "Нет данных")
            accelButton.text="АКСЕЛЕРОМЕТР ${if(accelReady)"✓" else ""}\n"+
                (if(accelFresh && State.linearAccelerationMps2.isFinite())
                    String.format(Locale.US,"%.2f м/с²",State.linearAccelerationMps2)
                    else "Нет данных")
            (gyroButton.background as? GradientDrawable)?.setStroke(
                dp(if(selectedSensor=="gyro")2 else 1),
                if(gyroReady)Color.rgb(70,197,125) else sky)
            (accelButton.background as? GradientDrawable)?.setStroke(
                dp(if(selectedSensor=="accel")2 else 1),
                if(accelReady)Color.rgb(70,197,125) else sky)
            sensorHelp.text=when{
                State.stationaryCalibrationRunning->
                    "Калибровка 5 с: машина стоит (OBD 0 км/ч). Не двигай магнитолу."
                State.gyroCalibrated && State.accelCalibrated ->
                    "✓ Смещения гироскопа и акселерометра сохранены. "+
                        "При потере сигнала галочка временно исчезает."
                else -> "Перед калибровкой остановись, подключи Vgate и "+
                    "дождись скорости 0 км/ч. Удерживать магнитолу не нужно — "+
                    "она неподвижно закреплена. 5 с, ≥40 измерений гироскопа, "+
                    "≥20 акселерометра. ${State.calibrationText}"
            }
            val externalFresh=State.gpsExternalUpdateMs>0L &&
                now-State.gpsExternalUpdateMs<8000L
            val internalFresh=State.gpsInternalUpdateMs>0L &&
                now-State.gpsInternalUpdateMs<8000L
            gpsText.text="USB GM906BT: ${if(externalFresh)"●" else "○"} "+
                State.gpsExternalStatus+"\n"+
                "Встроенный GNSS: ${if(internalFresh)"●" else "○"} "+
                State.gpsInternalStatus+"\n"+
                "Используется: ${State.gpsActiveSource}\n"+
                "Позиция: ${State.sourceDescription}"
            val obdFresh=State.speedUpdatedElapsedMs>0 &&
                now-State.speedUpdatedElapsedMs<4500
            obdText.text="VGATE / OBD     "+
                (if(obdFresh)String.format(Locale.US,"%.0f км/ч",
                    State.obdSpeedKmh()?:0.0) else "нет скорости")+
                "\n${State.deviceName} · ${State.modeLabel}"
            val session=FytRouteStore.load(this@FytHeadUnitActivity)
            infoText.text="Режим: ${State.navigationMode} · "+
                "Маршрут: ${if(session?.active==true)"ведение сохранено"
                    else if(session!=null)"цель сохранена" else "не выбран"}"+
                "\nСон/пробуждение: ${State.gpsWakeStatus}"+
                "\n${State.message}"
            adapterDialog?.let { dialog->
                if(dialog.isShowing){
                    val devices=State.devices.toList()
                    dialogKeys=devices.map{it.first}
                    val values=if(devices.isEmpty())
                        listOf("Сканирование Bluetooth / Vgate…")
                    else devices.map{it.second+" · "+it.first.substringAfter("|")}
                    dialogAdapter?.clear();dialogAdapter?.addAll(values)
                    dialogAdapter?.notifyDataSetChanged()
                }
            }
            tick.postDelayed(this,850L)
        }
    }

    private fun calibrate(sensor:String){
        selectedSensor=sensor
        AlertDialog.Builder(this)
            .setTitle(if(sensor=="gyro")"Калибровка гироскопа"
                else "Калибровка акселерометра")
            .setMessage("Два датчика калибруются совместно. Машина должна "+
                "стоять: нужна свежая скорость OBD = 0 км/ч. "+
                "Магнитола закреплена и не должна двигаться 5 секунд. "+
                "Галочки появятся только после успешной проверки ≥40 "+
                "измерений гироскопа и ≥20 ускорения и допустимого "+
                "смещения угловой скорости.\n\n${State.calibrationText}")
            .setPositiveButton("Калибровать 5 с"){_,_->
                launchService(NavService.CALIBRATE)
            }.setNegativeButton("Закрыть",null).show()
    }

    private fun showVgatePicker(){
        if(!hasPermissions()){askPermissions();return}
        launchService(NavService.SCAN)
        val adapter=ArrayAdapter<String>(this,
            android.R.layout.simple_list_item_1,mutableListOf("Поиск Vgate…"))
        dialogAdapter=adapter
        val dialog=AlertDialog.Builder(this)
            .setTitle("ELM / Vgate: Bluetooth Classic или BLE")
            .setAdapter(adapter){_,index->
                dialogKeys.getOrNull(index)?.let {
                    launchService(NavService.CONNECT,it)
                }
            }
            .setPositiveButton("Обновить поиск"){_,_->showVgatePicker()}
            .setNegativeButton("Закрыть",null)
            .create()
        adapterDialog=dialog
        dialog.setOnDismissListener{adapterDialog=null;dialogAdapter=null}
        dialog.show()
    }

    private fun showProtocol(){
        val titles=arrayOf("AUTO (CAN + K-Line)","CAN","K-Line","MUT-II")
        val actions=arrayOf(NavService.SET_AUTO,NavService.SET_CAN,
            NavService.SET_OBD,NavService.SET_MUT)
        AlertDialog.Builder(this).setTitle("Протокол Vgate")
            .setItems(titles){_,i->launchService(actions[i])}
            .setNegativeButton("Отмена",null).show()
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor=bg;window.navigationBarColor=bg
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10),dp(4),dp(10),dp(4))
            setOnApplyWindowInsetsListener{view,insets->
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout())
                view.setPadding(dp(10)+bars.left,dp(4)+bars.top,
                    dp(10)+bars.right,dp(4)+bars.bottom)
                insets
            }
        }
        setContentView(root)
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        header.addView(label("OBD GPS · FYT 7870",21f,true),
            LinearLayout.LayoutParams(0,dp(52),1f))
        alerts=AirAlertIndicatorsView(this)
        header.addView(alerts,LinearLayout.LayoutParams(dp(168),dp(52)))
        root.addView(header)
        val body=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))

        val leftScroll=ScrollView(this).apply{isFillViewport=true}
        body.addView(leftScroll,LinearLayout.LayoutParams(0,-1,1.02f))
        val left=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(3),dp(2),dp(7),0)
        }
        leftScroll.addView(left)
        left.addView(label("ДАТЧИКИ ДВИЖЕНИЯ",13f,true).apply{
            setTextColor(sky);setPadding(dp(6),dp(1),0,dp(3))
        })
        gyroButton=action("ГИРОСКОП"){calibrate("gyro")}
        accelButton=action("АКСЕЛЕРОМЕТР"){calibrate("accel")}
        buttonRow(left,74,gyroButton,accelButton)
        sensorHelp=label("Проверяю калибровку…",12f).apply{
            setTextColor(muted);setPadding(dp(7),dp(3),dp(7),dp(8))
        }
        left.addView(sensorHelp)
        left.addView(label("ДВА ИСТОЧНИКА GPS",13f,true).apply{setTextColor(sky)})
        gpsText=label("Ожидаю данные GNSS…",12.5f)
        gpsText.background=bgDrawable()
        gpsText.setPadding(dp(8),dp(8),dp(8),dp(8))
        left.addView(gpsText,LinearLayout.LayoutParams(-1,-2).apply{
            setMargins(0,dp(5),0,dp(6))
        })
        obdText=label("Ожидаю Vgate…",13f)
        obdText.background=bgDrawable()
        obdText.setPadding(dp(8),dp(7),dp(8),dp(7))
        left.addView(obdText)

        val right=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(8),dp(2),dp(3),0)
        }
        body.addView(right,LinearLayout.LayoutParams(0,-1,1.25f))
        right.addView(label("НАВИГАЦИЯ",13f,true).apply{setTextColor(sky)})
        buttonRow(right,74,
            action("ВЕКТОРНАЯ КАРТА\nИ МАРШРУТ"){startActivity(Intent(this,
                OfflineVectorNavigatorActivity::class.java))},
            action("ПРОДОЛЖИТЬ\nМАРШРУТ"){startActivity(Intent(this,
                OfflineVectorNavigatorActivity::class.java))})
        buttonRow(right,58,
            action("НАВИГАТОР"){startActivity(Intent(this,NavigatorActivity::class.java))},
            action("ОФЛАЙН-КАРТЫ"){startActivity(Intent(this,OfflineMapsActivity::class.java))})
        right.addView(label("OBD И РЕЗЕРВНОЕ ВЕДЕНИЕ",13f,true).apply{setTextColor(sky)})
        buttonRow(right,58,
            action("VGATE: ПОДКЛЮЧИТЬ"){showVgatePicker()},
            action("ПРОТОКОЛ"){showProtocol()})
        buttonRow(right,57,
            action("ВЕДЕНИЕ ПО\nДАТЧИКАМ"){launchService(NavService.START_DR)},
            action("ВЕРНУТЬ GPS"){launchService(NavService.STOP_DR)})
        infoText=label("Готово",12f)
        infoText.setTextColor(muted)
        infoText.setPadding(dp(9),dp(8),dp(9),dp(8))
        infoText.background=bgDrawable()
        right.addView(infoText,LinearLayout.LayoutParams(-1,0,1f).apply{
            setMargins(dp(4),dp(4),dp(4),dp(3))
        })
        // In normal wake Android resumes the foreground map activity.
        // After process death, reopening the launcher reopens a saved route.
        if(savedInstanceState==null && FytRouteStore.load(this)?.active==true){
            root.post{startActivity(Intent(this,OfflineVectorNavigatorActivity::class.java))}
        }
        root.post {askPermissions()}
    }

    override fun onResume(){
        super.onResume()
        if(hasPermissions())launchService(NavService.MONITOR)
        tick.removeCallbacks(uiUpdate)
        tick.post(uiUpdate)
    }
    override fun onPause(){
        tick.removeCallbacks(uiUpdate)
        adapterDialog?.dismiss()
        super.onPause()
    }
}
