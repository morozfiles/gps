package ua.obdgps.nav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowInsets
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.widget.*

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var alertDot: View
    private lateinit var alertLabel: TextView
    private val kyivClock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Kyiv"))

    private fun dip(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun paintAlertDot(color: Int) {
        alertDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dip(1), Color.DKGRAY)
        }
    }

    private fun showAlertStatus() {
        val alert = AirAlerts.snapshot()
        val color = when (alert.level) {
            AirAlerts.Level.CITY -> Color.rgb(217, 48, 37)
            AirAlerts.Level.OBLAST -> Color.rgb(255, 194, 37)
            AirAlerts.Level.CLEAR -> Color.rgb(27, 156, 70)
            AirAlerts.Level.UNKNOWN -> Color.rgb(141, 150, 160)
        }
        paintAlertDot(color)
        val timeLabel = if (alert.sourceUpdatedUtcMs > 0L)
            " · ${kyivClock.format(Instant.ofEpochMilli(alert.sourceUpdatedUtcMs))}"
        else ""
        val caption = when (alert.level) {
            AirAlerts.Level.CITY -> "Киев: тревога"
            AirAlerts.Level.OBLAST -> "Тревога: область"
            AirAlerts.Level.CLEAR -> "Без тревог (по данным)"
            AirAlerts.Level.UNKNOWN -> "Нет данных о тревогах"
        }
        alertLabel.text = caption + timeLabel
        alertDot.contentDescription = alert.explanation
    }

    private fun addAlertHeader() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val heading = TextView(this).apply {
            text = "OBD GPS Navigator — Space Star K-Line"
            textSize = 21f
            setTextColor(Color.BLACK)
        }
        top.addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val indicator = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT
            setPadding(dip(8), 0, 0, 0)
        }
        alertDot = View(this).apply {
            isClickable = true
            setOnClickListener {
                Toast.makeText(this@MainActivity,
                    AirAlerts.snapshot().explanation + "\nИсточник: Tryvoha.online (неофициально)",
                    Toast.LENGTH_LONG).show()
            }
        }
        indicator.addView(alertDot, LinearLayout.LayoutParams(dip(22), dip(22)).apply {
            gravity = Gravity.RIGHT
        })
        alertLabel = TextView(this).apply {
            text = "Проверка тревог..."
            textSize = 11f
            gravity = Gravity.RIGHT
            setTextColor(Color.DKGRAY)
            maxWidth = dip(148)
        }
        indicator.addView(alertLabel)
        top.addView(indicator)
        root.addView(top)
        val attribution = TextView(this).apply {
            text = "Тревоги: Tryvoha.online · неофициальные данные; проверяй сирены и официальные оповещения"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(0, dip(4), 0, dip(10))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tryvoha.online/")))
                } catch (_: Exception) {}
            }
        }
        root.addView(attribution)
        showAlertStatus()
    }
    private var picker: AlertDialog? = null
    private var deviceAdapter: ArrayAdapter<String>? = null
    private var pickerKeys = listOf<String>()
    private fun updateDevicePicker() {
        val list = State.devices.toList()
        pickerKeys = list.map { it.first }
        deviceAdapter?.let { adapter ->
            adapter.clear()
            adapter.addAll(
                if (list.isEmpty()) listOf("Поиск устройств... Включи зажигание и Bluetooth")
                else list.map { (key, name) -> "$name\n${key.substringAfter('|')}" }
            )
            adapter.notifyDataSetChanged()
        }
    }
    private fun showDevicePicker() {
        picker?.dismiss()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        deviceAdapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle("Выбери Bluetooth ELM327")
            .setAdapter(adapter) { _, index ->
                pickerKeys.getOrNull(index)?.let { send(NavService.CONNECT, it) }
            }
            .setPositiveButton("Искать снова") { _, _ ->
                State.devices.clear()
                send(NavService.SCAN)
                showDevicePicker()
            }
            .setNegativeButton("Закрыть", null)
            .create()
        picker = dialog
        dialog.setOnDismissListener { if (picker === dialog) { picker = null; deviceAdapter = null } }
        dialog.show()
        updateDevicePicker()
    }
    private val refresh = object: Runnable {
        override fun run() {
            AirAlerts.refreshIfDue()
            showAlertStatus()
            status.text = "Статус: ${State.message}\nELM327: ${State.deviceName}\nСкорость: ${State.speed ?: "—"} км/ч\nПротокол: ${State.modeLabel}\nDead Reckoning: ${State.dr}\nРасчёт: ${State.position}"
            if (picker != null) updateDevicePicker()
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
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            clipToPadding = false
            // Android 15/16 enforces edge-to-edge drawing: reserve the
            // status bar, display cutout and gesture navigation space.
            setOnApplyWindowInsetsListener { view, windowInsets ->
                val bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                windowInsets
            }
        }
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(30,30,30,40) }
        scroll.addView(root); setContentView(scroll)
        addAlertHeader()
        root.addView(TextView(this).apply { text="Mitsubishi Space Star 2003 1.6 АКПП · Vgate iCar Pro 2S BLE · ISO 9141 / KWP2000" })
        root.addView(TextView(this).apply { text="Закрепите телефон неподвижно. Не полагайтесь на расчёт как на единственный источник навигации." })
        status = TextView(this).apply { textSize=16f; setPadding(0,20,0,20) }; root.addView(status)
        addButton(root,"1. Разрешения Bluetooth и местоположения") { askPermissions() }
        addButton(root,"2. Найти и выбрать Bluetooth ELM327") {
            State.devices.clear()
            send(NavService.SCAN)
            showDevicePicker()
        }
        addButton(root,"3. Выбрать протокол OBD2 / Mitsubishi MUT-II") {
            val modes = arrayOf("OBD2 K-Line (рекомендуется)", "MUT-II экспериментальный (скорость 0x2F)")
            AlertDialog.Builder(this)
                .setTitle("Mitsubishi Space Star 2003 — протокол")
                .setSingleChoiceItems(modes, if (State.mutMode) 1 else 0) { dlg, index ->
                    send(if(index == 1) NavService.SET_MUT else NavService.SET_OBD)
                    dlg.dismiss()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        addButton(root,"4. Настройки разработчика: приложение фиктивных местоположений") {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        addButton(root,"5. Включить Dead Reckoning") { send(NavService.START_DR) }
        addButton(root,"Выключить Dead Reckoning") { send(NavService.STOP_DR) }
        addButton(root,"Открыть Google Maps") { openGoogleMaps() }
        addButton(root,"Остановить") { send(NavService.EXIT) }
        root.addView(TextView(this).apply { text="MUT-II является экспериментальным: фирменная инициализация 0x01/15625 бод может быть не поддержана ELM327. Если адаптер не отвечает, приложение возвращается к OBD2 K-Line. Программа только читает параметры ЭБУ." })
        root.addView(TextView(this).apply { text="GPS используется для начальной точки; без OBD-скорости вычисление движения приостанавливается. Подмена GPS определяется только по резким скачкам." })
    }
    private fun openGoogleMaps() {
        val mapsPackage = "com.google.android.apps.maps"

        // Android 11+ package visibility: declared in AndroidManifest <queries>.
        // Prefer launching Maps normally, without forcing a destination.
        try {
            val launch = packageManager.getLaunchIntentForPackage(mapsPackage)
            if (launch != null) {
                startActivity(launch)
                return
            }
        } catch (_: Exception) {
            // Fall through to a verified ACTION_VIEW intent.
        }

        try {
            val mapsLink = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/"))
                .setPackage(mapsPackage)
            startActivity(mapsLink)
            return
        } catch (_: ActivityNotFoundException) {
            // Maps may be missing or disabled. Try a browser instead.
        } catch (_: SecurityException) {
            // The package may reject the intent; try the browser instead.
        }

        try {
            val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            startActivity(browser)
            State.message = "Google Maps открылся через браузер"
        } catch (e: Exception) {
            val error = "Не удалось открыть Google Maps: ${e.localizedMessage ?: "нет подходящего приложения"}"
            State.message = error
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); picker?.dismiss(); super.onPause() }
}
