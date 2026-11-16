package ua.obdgps.nav

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private var listed = ""
    private val refresh = object: Runnable {
        override fun run() {
            status.text = "Статус: ${State.message}\nELM327: ${State.deviceName}\nСкорость: ${State.speed ?: "—"} км/ч\nDead Reckoning: ${State.dr}\nРасчёт: ${State.position}"
            val key = State.devices.toString()
            if (listed != key) {
                listed = key
                devices.removeAllViews()
                for ((address,name) in State.devices) addButton(devices,"$name ($address)") { send(NavService.CONNECT,address) }
            }
            handler.postDelayed(this,750)
        }
    }
    private fun addButton(parent: LinearLayout, caption: String, click: ()->Unit) {
        parent.addView(Button(this).apply { text = caption; isAllCaps = false; setOnClickListener { click() } })
    }
    private fun send(action: String, address: String? = null) {
        if (!hasPermissions()) { askPermissions(); return }
        val i = Intent(this, NavService::class.java).setAction(action)
        if (address != null) i.putExtra("address",address)
        try { startForegroundService(i) } catch (e: Exception) { State.message = e.message ?: "Ошибка запуска" }
    }
    private fun hasPermissions() = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun askPermissions() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS),12)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(30,30,30,40) }
        scroll.addView(root); setContentView(scroll)
        root.addView(TextView(this).apply { text="OBD GPS Navigator — тестовая версия"; textSize=23f })
        root.addView(TextView(this).apply { text="Закрепите телефон неподвижно. Не полагайтесь на расчёт как на единственный источник навигации." })
        status = TextView(this).apply { textSize=16f; setPadding(0,20,0,20) }; root.addView(status)
        addButton(root,"1. Разрешения Bluetooth и местоположения") { askPermissions() }
        addButton(root,"2. Найти BLE ELM327") { State.devices.clear(); listed=""; send(NavService.SCAN) }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(devices)
        addButton(root,"3. Настройки разработчика: приложение фиктивных местоположений") {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        addButton(root,"4. Включить Dead Reckoning") { send(NavService.START_DR) }
        addButton(root,"Выключить Dead Reckoning") { send(NavService.STOP_DR) }
        addButton(root,"Открыть Google Maps") {
            val i = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            if (i!=null) startActivity(i) else State.message="Google Maps не найден"
        }
        addButton(root,"Остановить") { send(NavService.EXIT) }
        root.addView(TextView(this).apply { text="GPS используется для начальной точки; при отсутствии OBD-скорости вычисление движения приостанавливается. Подмена GPS определяется только по резким скачкам." })
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
