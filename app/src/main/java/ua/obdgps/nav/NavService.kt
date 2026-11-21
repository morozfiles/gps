package ua.obdgps.nav

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import java.util.*
import java.io.OutputStream
import kotlin.math.*

@SuppressLint("MissingPermission")
class NavService: Service(), LocationListener {
    companion object {
        const val SCAN="SCAN"
        const val CONNECT="CONNECT"
        const val START_DR="START_DR"
        const val START_ROUTE_DR="START_ROUTE_DR"
        const val STOP_DR="STOP_DR"
        const val EXIT="EXIT"
        const val SET_MUT="SET_MUT"
        const val SET_OBD="SET_OBD"
        const val SET_CAN="SET_CAN"
        const val SET_AUTO="SET_AUTO"
        const val SET_MANUAL_START="SET_MANUAL_START"
        const val CLEAR_MANUAL_START="CLEAR_MANUAL_START"
        const val AUTO_ON="AUTO_ON"
        const val AUTO_OFF="AUTO_OFF"
        const val CALIBRATE="CALIBRATE"
        const val CALIBRATE_FYT_GYRO="CALIBRATE_FYT_GYRO"
        const val ALIGN_COMPASS="ALIGN_COMPASS"
        const val CHECK_COMPASS="CHECK_COMPASS"
        const val REVERSE="REVERSE"
        const val FORWARD="FORWARD"
        const val MONITOR="MONITOR"
        const val START_DIAGNOSTIC="START_DIAGNOSTIC"
        const val STOP_DIAGNOSTIC="STOP_DIAGNOSTIC"
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lm: LocationManager
    private lateinit var motion: MotionSensors
    private lateinit var hybridLocator: HybridLocator
    private lateinit var settings: NavigatorPrefs
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var sppSocket: BluetoothSocket? = null
    private var sppOutput: OutputStream? = null
    private var sppReader: Thread? = null
    private var transport = "BLE"
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val bt by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private val dr = DeadReckoner()
    // Separate test estimate: receives OBD + gyro + direct NETWORK fixes only.
    private val comparisonDr=IndependentNetworkDr()
    private lateinit var learnedDr:AdaptiveDrLearning
    private val gpsConsensus=GpsFixConsensus()
    private var gpsQuarantined=false
    private var quarantineRecoveryCount=0
    private var quarantineRecoveryLast=0L
    private var quarantineRecoverySince=0L
    private var quarantineCandidate:Location?=null
    private var gps: Location? = null
    private var gpsTime = 0L
    private var lastExternalAcceptedMs=0L
    private var acceptedExternalReceiver=false
    private var speedTime = 0L
    private var tickTime = 0L
    private var lastDrStart = 0L
    private var gpsInvalidSince = 0L
    private var gpsRecoveryCount = 0
    private var gpsRecoveryLast = 0L
    private var gpsRecoverySince = 0L
    // GPS may briefly look valid during spoofing. Correct DR after 3
    // independently corroborated fixes; auto-return only after a 30 s
    // Five verified seconds to recover from normal signal loss. Suspected
    // spoofing still requires independent multi-sample quarantine release.
    private val gpsCorrectionFixes=3
    private val gpsReturnFixes=5
    private val gpsReturnStableMs=5_000L
    private val gpsRecoveryMaxGapMs=5_000L
    private var lastRejectedGps = 0L
    private var lastGpsTeleport = 0L
    private var lastAcceptedGps = 0L
    private var lastTrustedHeading:Double?=null
    private var lastTrustedHeadingMs=0L
    private var previousNetworkFix:State.NavFix?=null
    private var networkCorrectionSamples=0
    private var lastNetworkCorrectionMs=0L
    private var lastCorrectionSource=""
    private var lastGpsCorrectionMs=0L
    private var manualMode = false
    private var reverse = false
    private var autoEnabled = true
    private var connected = false
    private val responseBuffer = StringBuilder()
    private val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST96")
    // Experimental Mitsubishi MUT-II K-line startup. Requires ELM firmware
    // with custom slow-init address and 15625 baud support.
    private val mutSetup = listOf("ATSP3", "ATAL", "ATKW0", "ATIIA01", "ATIB15", "ATSI")
    private var mutMode = false
    private var mutSetupIndex = 0
    private var mutFailures = 0

    private val klineProtocols = listOf(
        "3" to "ISO 9141-2",
        "4" to "KWP2000 5-baud",
        "5" to "KWP2000 fast"
    )
    private val canProtocols = listOf(
        "6" to "CAN 11-bit 500k",
        "7" to "CAN 29-bit 500k",
        "8" to "CAN 11-bit 250k",
        "9" to "CAN 29-bit 250k"
    )
    private var protocols = klineProtocols + listOf("0" to "Auto OBD2")
    private var protocolMode="KLINE"
    private fun candidates(mode:String):List<Pair<String,String>> = when(mode) {
        "CAN" -> canProtocols+listOf("0" to "Auto OBD2")
        "AUTO" -> listOf("0" to "Auto OBD2")+canProtocols+klineProtocols
        else -> klineProtocols+listOf("0" to "Auto OBD2")
    }
    private fun protocolLabel(mode:String)=when(mode) {
        "MUT" -> "MUT-II (эксперимент)"
        "CAN" -> "OBD2 CAN (ISO 15765-4)"
        "AUTO" -> "OBD2 (авто K-Line / CAN)"
        else -> "OBD2 K-Line"
    }
    private var setupIndex = 0
    private var protocolIndex = 0
    private var configuredProtocol = -1
    private var failedSpeedRequests = 0
    private var lastPoll = 0L
    private var lastSentAt = 0L
    private var pendingCommand: String? = null
    private var uartReady = false
    private val loop = object: Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (connected && uartReady && (tx != null || sppOutput != null)) {
                // Never send another ELM command until the preceding prompt (>) arrives.
                // Initializing ISO9141 / KWP can take several seconds.
                if (pendingCommand != null && now - lastSentAt > 7000L) {
                    processElmReply("TIMEOUT")
                }
                if (pendingCommand == null && now - lastPoll >= 900L) {
                    val command = when {
                        setupIndex < commands.size -> commands[setupIndex]
                        mutMode && mutSetupIndex < mutSetup.size -> mutSetup[mutSetupIndex]
                        mutMode -> "2F"
                        configuredProtocol != protocolIndex -> "ATSP${protocols[protocolIndex].first}"
                        else -> "010D"
                    }
                    write(command)
                }
            }
            AirAlerts.refreshIfDue()
            motion.updateCalibrationTimers(now)
            updateNavigation(now)
            if(State.diagnosticRunning){
                val obd=correctedSpeed()?.takeIf{
                    State.speed!=null && now-speedTime<3500L
                }
                comparisonDr.advance(now,obd)?.let{pose->
                    State.addNetworkDrPoint(pose.lat,pose.lon,pose.bearing,
                        obd?:0.0,now)
                }
                State.diagnosticNetworkStatus=comparisonDr.lastNetworkStatus
                State.learnedDrStatus=learnedDr.status()
                State.learnedDrDetail=learnedDr.detail()
                State.diagnosticText=if(obd==null)
                    "GPS отключён для DR · нет свежей скорости OBD"
                else "Имитация отсутствия GPS · DR: OBD + гироскоп + сеть"
            }
            publishActualNavigationPosition(now)
            tickTime=now
            handler.postDelayed(this,250)
        }
    }
    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        settings = NavigatorPrefs(this)
        learnedDr=AdaptiveDrLearning(this)
        State.learnedDrEnabled=settings.learnedDrEnabled
        State.learnedDrStatus=learnedDr.status()
        State.learnedDrDetail=learnedDr.detail()
        AirAlerts.initialize(this)
        State.obdFactor = settings.obdFactor
        autoEnabled = true
        settings.autoEnabled = true
        State.autoEnabled = true
        protocolMode=settings.protocolMode
        mutMode=protocolMode=="MUT"
        protocols=candidates(protocolMode)
        State.mutMode=mutMode
        State.modeLabel=protocolLabel(protocolMode)
        State.manualStart=settings.manualStart
        if(State.manualStart!=null) {
            val start=State.manualStart!!
            State.sourceDescription="Ручная стартовая точка сохранена"
            State.navFix=State.NavFix(start.lat,start.lon,start.bearing,30f,
                "Ручной старт",SystemClock.elapsedRealtime())
        }
        State.deviceName = settings.adapterName ?: "Не выбран"
        State.directionText="Не определено — требуется калибровка"
        State.directionConfirmed=false
        State.reverseSelected=false
        hybridLocator = HybridLocator(this) { location,source ->
            acceptNetworkLocation(location,source)
        }
        motion = MotionSensors(this) { yaw, dt ->
            if(State.dr){
                val fresh=State.speed!=null &&
                    SystemClock.elapsedRealtime()-speedTime<3500L
                val kmh=if(fresh)correctedSpeed() else null
                val scale=if(State.learnedDrEnabled && kmh!=null)
                    learnedDr.yawGain(kmh,Math.toDegrees(yaw))
                    else 1.0
                dr.turn(yaw*scale,dt)
            }
            // Training comparison remains COMPLETELY uncorrected. It is
            // independent of the learned network and of the primary DR.
            if(State.diagnosticRunning)comparisonDr.onGyroYaw(yaw,dt)
        }
        motion.start()
        State.navigationMode = "GPS"
        State.manualForced = false
        State.dr = false
        State.gpsText = "Ожидаем GPS"
        State.fytGpsActiveSource="Ожидаю GM906BT / внутренний GPS"
        State.fytExternalGpsMs=0L
        State.fytInternalGpsMs=0L
        reverse = false
        val channel = NotificationChannel("nav","OBD GPS Navigator",NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val intent = PendingIntent.getActivity(this,0,Intent(this,GoogleDrMapActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this,"nav").setContentTitle("OBD GPS Navigator").setContentText("GPS/OBD monitoring")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(intent).build()
        startForeground(1,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
            PackageManager.PERMISSION_GRANTED
        val coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==
            PackageManager.PERMISSION_GRANTED
        if(fine) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,this,Looper.getMainLooper())
            }catch(_:Exception){}
        }
        // Approximate-only permission still gives Wi-Fi/cellular location.
        if(fine || coarse)hybridLocator.start()
        tickTime=SystemClock.elapsedRealtime()
        handler.post(loop)
    }
    override fun onStartCommand(i: Intent?,flags:Int,startId:Int): Int {
        when(i?.action) {
            SCAN -> scan()
            CONNECT -> i.getStringExtra("address")?.let { connectChoice(it) }
            SET_MUT -> changeProtocol("MUT")
            SET_OBD -> changeProtocol("KLINE")
            SET_CAN -> changeProtocol("CAN")
            SET_AUTO -> changeProtocol("AUTO")
            SET_MANUAL_START -> {
                val lat=i.getDoubleExtra("lat",Double.NaN)
                val lon=i.getDoubleExtra("lon",Double.NaN)
                val bearing=i.getDoubleExtra("bearing",Double.NaN)
                if(lat.isFinite()&&lat in -85.0..85.0&&lon.isFinite()&&
                    lon in -180.0..180.0&&bearing.isFinite()) {
                    val item=NavigatorPrefs.ManualStart(lat,lon,
                        ((bearing%360)+360)%360)
                    settings.manualStart=item
                    State.manualStart=item
                    State.message="Старт вручную: %.5f, %.5f; курс %.0f°"
                        .format(Locale.US,lat,lon,item.bearing)
                    if(!State.dr) {
                        State.navFix=State.NavFix(lat,lon,item.bearing,
                            30f,"Ручной старт",SystemClock.elapsedRealtime())
                        State.sourceDescription="Старт задан вручную"
                    }
                } else State.message="Некорректные координаты стартовой точки"
            }
            CLEAR_MANUAL_START -> {
                settings.manualStart=null
                State.manualStart=null
                State.message="Ручная стартовая точка удалена"
            }
            START_DR -> startDr(force=true)
            START_ROUTE_DR -> if(State.activeRouting)startDr(force=false)
            REVERSE -> applyDetectedDirection(true,"выбрано вручную")
            FORWARD -> applyDetectedDirection(false,"выбрано вручную")
            START_DIAGNOSTIC -> startDiagnostic()
            STOP_DIAGNOSTIC -> {
                State.stopDiagnostic()
                comparisonDr.stop()
                learnedDr.resetSegment()
                learnedDr.save()
            }
            STOP_DR -> {
                stopDr()
                State.message="GPS выбран; автоматическое переключение включено"
            }
            AUTO_ON, AUTO_OFF -> {
                autoEnabled=true
                State.autoEnabled=true
                settings.autoEnabled=true
                State.message="Автопереключение всегда включено"
            }
            CALIBRATE -> {
                val text=motion.calibrate(State.speed,SystemClock.elapsedRealtime()-speedTime<3000)
                State.message=text
                if(text.startsWith("Калибровка:")) handler.postDelayed({
                    State.message=motion.finishCalibration()
                },5000)
            }
            CALIBRATE_FYT_GYRO -> {
                val fresh=State.speed!=null &&
                    SystemClock.elapsedRealtime()-speedTime<3500L
                val text=motion.calibrateGyroOnly(State.speed,fresh)
                State.message=text
                if(text.startsWith("Калибровка:"))handler.postDelayed({
                    State.message=motion.finishCalibration()
                },5000)
            }
            CHECK_COMPASS -> {
                val fresh=SystemClock.elapsedRealtime()-speedTime<3500L
                State.message=motion.beginCompassCheck(State.speed,fresh)
            }
            ALIGN_COMPASS -> {
                // Does not require the driver to interact with the phone while moving.
                // Arm stable GPS calibration now; complete automatically later.
                State.message=motion.resetVehicleAlignment()
            }
            MONITOR -> {
                val key=settings.adapterKey
                if(key!=null && !connected &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED &&
                    bt?.isEnabled==true) {
                    State.message="Подключение к сохранённому ELM327..."
                    connectChoice(key,save=false)
                } else if(!connected) State.message="Мониторинг датчиков запущен"
            }
            EXIT -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }
    private fun changeProtocol(mode:String) {
        if(mode !in setOf("AUTO","CAN","KLINE","MUT")) return
        settings.protocolMode=mode
        protocolMode=mode
        mutMode=mode=="MUT"
        State.mutMode=mutMode
        State.modeLabel=protocolLabel(mode)
        protocols=candidates(mode)
        setupIndex=0
        mutSetupIndex=0
        mutFailures=0
        protocolIndex=0
        configuredProtocol=-1
        failedSpeedRequests=0
        pendingCommand=null
        responseBuffer.clear()
        speedTime=0
        State.speed=null
        State.speedUpdatedElapsedMs=0L
        State.message="Настройка протокола: "+State.modeLabel
    }
    private fun changeMode(useMut:Boolean) {
        // Reinitialize the selected protocol after Bluetooth reconnection.
        changeProtocol(if(useMut)"MUT" else protocolMode.takeIf{it!="MUT"}?:"KLINE")
    }
    private fun fallbackFromMut(reason:String) {
        changeProtocol("KLINE")
        State.modeLabel="OBD2 K-Line (MUT недоступен)"
        State.message="MUT-II недоступен: $reason; пробую OBD2 K-Line"
    }
    private fun scan() {
        try {
            stopScan()
            State.devices.clear()
            // Show paired classic devices and BLE devices separately. Some
            // iCar Pro 2S variants advertise as Android-Vlink over RFCOMM.
            bt?.bondedDevices?.forEach { d ->
                val name=d.name ?: "Bluetooth без имени"
                if(d.type==BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                   d.type==BluetoothDevice.DEVICE_TYPE_DUAL ||
                   d.type==BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                    State.devices["SPP|${d.address}"]="Сопряжён: $name [Classic]"
                if(d.type==BluetoothDevice.DEVICE_TYPE_LE ||
                   d.type==BluetoothDevice.DEVICE_TYPE_DUAL)
                    State.devices["BLE|${d.address}"]="Сопряжён: $name [BLE]"
            }
            val scan=bt?.bluetoothLeScanner
            if(scan==null) {
                State.message=if(bt==null)
                    "FYT: системный Bluetooth недоступен (возможно, управление через MCU). Нужен Android-совместимый ELM327."
                    else "Bluetooth выключен или BLE недоступен; попробуй сопряжённый Classic ELM327"
                return
            }
            scanner=scan
            val cb=object: ScanCallback() {
                override fun onScanResult(type:Int,result:ScanResult) {
                    val address=result.device.address
                    val name=result.scanRecord?.deviceName
                        ?: runCatching { result.device.name }.getOrNull() ?: "BLE без имени"
                    handler.post { State.devices["BLE|$address"]="Найден: $name [BLE]" }
                }
                override fun onScanFailed(code:Int) {
                    handler.post { State.message="Ошибка поиска BLE: $code" }
                }
            }
            scanCallback=cb
            scan.startScan(cb)
            State.message="Поиск BLE и сопряжённых устройств (15 секунд)"
            handler.postDelayed({stopScan()},15000)
        } catch(e:Exception) {State.message="Поиск Bluetooth: ${e.message}"}
    }
    private fun stopScan() {
        try { scanCallback?.let {scanner?.stopScan(it)} } catch(_:Exception) {}
        scanCallback=null
    }
    private fun disconnectTransport() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt=null
        tx=null
        try { sppSocket?.close() } catch (_: Exception) {}
        sppSocket=null
        sppOutput=null
        connected=false
        uartReady=false
        pendingCommand=null
        State.speed=null
        State.speedUpdatedElapsedMs=0L
    }
    private fun connectChoice(choice:String, save:Boolean=true) {
        val selectedName=State.devices[choice] ?: settings.adapterName ?: choice
        if(save) {
            settings.adapterKey=choice
            settings.adapterName=selectedName
        }
        State.deviceName=selectedName
        val protocol=choice.substringBefore("|")
        val address=choice.substringAfter("|",choice)
        if(protocol=="SPP") connectClassic(address) else connect(address)
    }
    private fun connectClassic(address:String) {
        stopScan()
        disconnectTransport()
        transport="SPP"
        State.deviceName=State.devices["SPP|$address"] ?: settings.adapterName ?: address
        changeMode(mutMode)
        State.message="Подключение Bluetooth Classic к $address"
        val device=bt?.getRemoteDevice(address) ?: run {
            State.message="FYT: Bluetooth адаптер Android недоступен. Проверь режим BT/ELM327."
            return
        }
        val socket=device.createRfcommSocketToServiceRecord(
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
        sppSocket=socket
        sppReader=Thread {
            try {
                bt?.cancelDiscovery()
                socket.connect()
                val output=socket.outputStream
                handler.post {
                    if (sppSocket===socket) {
                        sppOutput=output
                        connected=true
                        uartReady=true
                        State.message="Bluetooth Classic подключён: ELM327"
                    }
                }
                val input=socket.inputStream
                val buffer=ByteArray(256)
                while(!Thread.currentThread().isInterrupted) {
                    val n=input.read(buffer)
                    if(n<0) break
                    val packet=buffer.copyOf(n)
                    handler.post { if(sppSocket===socket) feed(packet) }
                }
            } catch(e:Exception) {
                handler.post {
                    if(sppSocket===socket)
                        State.message="Bluetooth Classic: ${e.message}"
                }
            } finally {
                handler.post {
                    if(sppSocket===socket) {
                        connected=false;uartReady=false;sppOutput=null
                        State.speed=null
        State.speedUpdatedElapsedMs=0L
                        try { socket.close() } catch(_:Exception){}
                    }
                }
            }
        }.also {it.name="ELM327-SPP";it.start()}
    }
    private fun connect(address:String) {
        stopScan()
        try {
            disconnectTransport()
            transport="BLE"
            changeMode(mutMode)
            State.deviceName=State.devices["BLE|$address"] ?: settings.adapterName ?: address
            val device=bt?.getRemoteDevice(address) ?: run {
                State.message="FYT: системный Bluetooth отсутствует; BLE ELM327 недоступен"
                return
            }
            gatt=device.connectGatt(this,false,object:BluetoothGattCallback(){
                override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int) {
                    handler.post {
                        if (gatt !== g) return@post
                        if (newState==BluetoothProfile.STATE_CONNECTED && status==BluetoothGatt.GATT_SUCCESS) {
                            State.message="BLE подключён, ищу сервис Vgate iCar Pro 2S"
                            connected=true
                            g.discoverServices()
                        } else {
                            State.message="BLE отключён: $status"
                            connected=false; uartReady=false; pendingCommand=null
                            State.speed=null
        State.speedUpdatedElapsedMs=0L
                        }
                    }
                }
                override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {
                    handler.post {
                        if (gatt !== g) return@post
                        if(status!=BluetoothGatt.GATT_SUCCESS) {
                            State.message="Ошибка GATT $status";return@post
                        }
                        val entries=g.services.flatMap { srv->srv.characteristics.map { srv to it } }
                        fun canWrite(c:BluetoothGattCharacteristic)=
                            c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0
                        fun canNotify(c:BluetoothGattCharacteristic)=
                            c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0
                        // Confirmed on some iCar Pro 2S BLE 5.3 firmware variants.
                        val vgateService=UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
                        val vgateTx=UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")
                        val vgateRx=UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
                        val txKnown=entries.firstOrNull { (svc,c)->svc.uuid==vgateService && c.uuid==vgateTx && canWrite(c) }
                        val rxKnown=entries.firstOrNull { (svc,c)->svc.uuid==vgateService && c.uuid==vgateRx && canNotify(c) }
                        val known=if(txKnown!=null && rxKnown!=null) txKnown.second to rxKnown.second else null
                        // Fallback for different Vgate/ELM327 BLE firmware versions.
                        val fallback=entries.firstNotNullOfOrNull { (service,write) ->
                            if(!canWrite(write)) null else entries.firstOrNull { (srv,read)->
                                srv.uuid==service.uuid && canNotify(read)
                            }?.let { write to it.second }
                        }
                        val pair=known ?: fallback
                        if(pair==null) {State.message="BLE UART не найден (нет write+notify)";return@post}
                        tx=pair.first
                        val notify=pair.second
                        State.message=if(known!=null) "Vgate BLE UART 18F0 найден" else "BLE UART найден, пробую K-Line"
                        if(!g.setCharacteristicNotification(notify,true)) {
                            State.message="Не удалось подписаться на BLE уведомления";return@post
                        }
                        val desc=notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if(desc!=null) {
                            val bytes=if(notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0)
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else
                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            val result=g.writeDescriptor(desc,bytes)
                            if(result!=BluetoothStatusCodes.SUCCESS) {
                                State.message="Ошибка подписки BLE: $result"
                            }
                        } else {
                            uartReady=true
                            State.message="ELM327: инициализация K-Line"
                        }
                    }
                }
                override fun onDescriptorWrite(g:BluetoothGatt,descriptor:BluetoothGattDescriptor,status:Int) {
                    handler.post {
                        if (gatt !== g) return@post
                        if(status==BluetoothGatt.GATT_SUCCESS) {
                            uartReady=true
                            State.message="ELM327: инициализация K-Line"
                        } else State.message="BLE notifications: ошибка $status"
                    }
                }
                override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray) {
                    handler.post { if(gatt===g) feed(value) }
                }
            }, BluetoothDevice.TRANSPORT_LE)
            State.message="Подключение к $address"
        } catch(e:Exception) { State.message="Connection error: ${e.message}" }
    }
    private fun write(command:String) {
        try {
            val payload="$command\r".toByteArray(Charsets.US_ASCII)
            val sent=if(transport=="SPP") {
                val out=sppOutput ?: return
                out.write(payload)
                out.flush()
                true
            } else {
                val g=gatt ?: return
                val c=tx ?: return
                val kind=if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE !=0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(c,payload,kind)==BluetoothStatusCodes.SUCCESS
            }
            lastPoll=SystemClock.elapsedRealtime()
            if(sent) {
                pendingCommand=command
                lastSentAt=lastPoll
                responseBuffer.clear()
                if(command.startsWith("ATSP") && !mutMode)
                    State.message="K-Line: проверяю ${protocols[protocolIndex].second}"
                else if(mutMode && mutSetupIndex<mutSetup.size)
                    State.message="MUT-II: настройка $command"
            } else State.message="Не удалось отправить $command через Bluetooth"
        } catch(e:Exception) {
            pendingCommand=null
            State.message="Ошибка записи $command: ${e.message}"
        }
    }
    private fun feed(bytes:ByteArray) {
        responseBuffer.append(bytes.toString(Charsets.US_ASCII).replace("\u0000",""))
        if(responseBuffer.length>4096) responseBuffer.clear()
        var end=responseBuffer.indexOf(">")
        while(end>=0) {
            val reply=responseBuffer.substring(0,end)
            responseBuffer.delete(0,end+1)
            processElmReply(reply)
            end=responseBuffer.indexOf(">")
        }
    }
    private fun processElmReply(reply:String) {
        val command=pendingCommand ?: return
        pendingCommand=null
        val upper=reply.uppercase()
        val failed=upper.contains("TIMEOUT") || upper.contains("ERROR") ||
                   upper.contains("UNABLE TO CONNECT") || upper.trim()=="?" ||
                   upper.contains("BUS INIT: ERROR") || upper.contains("STOPPED")
        if(setupIndex<commands.size && command==commands[setupIndex]) {
            setupIndex++
            return
        }
        if(mutMode) {
            if(mutSetupIndex<mutSetup.size && command==mutSetup[mutSetupIndex]) {
                if(failed) {
                    fallbackFromMut("$command: ${upper.takeLast(42).trim()}")
                } else {
                    mutSetupIndex++
                    State.message="MUT-II: ${command} принят (${mutSetupIndex}/${mutSetup.size})"
                }
                return
            }
            if(command=="2F") {
                val kmh=MitsubishiMut.parseSpeed(reply)
                if(kmh!=null) {
                    State.speed=kmh
                    speedTime=SystemClock.elapsedRealtime()
                    State.speedUpdatedElapsedMs=speedTime
                    mutFailures=0
                    State.message="MUT-II 0x2F: $kmh км/ч (проверить в движении)"
                } else {
                    mutFailures++
                    val detail=upper.replace("\r"," ").replace("\n"," ").trim().takeLast(60)
                    State.message="MUT-II: нет валидного ответа 0x2F ($mutFailures/3): $detail"
                    if(mutFailures>=3)
                        fallbackFromMut("нет MUT ответа; нужен raw K-Line или другой интерфейс")
                }
                return
            }
        }
        if(command.startsWith("ATSP")) {
            if(failed) nextProtocol("ELM не принял $command")
            else configuredProtocol=protocolIndex
            return
        }
        if(command=="010D") {
            val kmh=Obd.parseSpeed(reply)
            if(kmh!=null) {
                State.speed=kmh
                speedTime=SystemClock.elapsedRealtime()
                State.speedUpdatedElapsedMs=speedTime
                failedSpeedRequests=0
                State.message="${protocols[protocolIndex].second}: $kmh км/ч (PID 010D)"
            } else {
                failedSpeedRequests++
                val detail=upper.replace("\r"," ").replace("\n"," ").trim().takeLast(54)
                State.message="${protocols[protocolIndex].second}: нет скорости ($failedSpeedRequests/3): $detail"
                if(failedSpeedRequests>=3 && protocolIndex<protocols.lastIndex)
                    nextProtocol("PID 010D не ответил")
            }
        }
    }
    private fun nextProtocol(reason:String) {
        if(protocolIndex<protocols.lastIndex) {
            protocolIndex++
            configuredProtocol=-1
            failedSpeedRequests=0
            State.message="$reason; пробую ${protocols[protocolIndex].second}"
        } else State.message="$reason; стандартный PID 010D недоступен"
    }
    private fun startDiagnostic(){
        val now=SystemClock.elapsedRealtime()
        if(State.diagnosticRunning){
            State.message="Два трека уже записываются"
            return
        }
        if(State.speed==null || now-speedTime>=3500L ||
            correctedSpeed()==null){
            State.diagnosticText="Нет свежей скорости OBD. Подключи ELM327, "+
                "чтобы тест DR не использовал GPS-скорость."
            State.message=State.diagnosticText
            return
        }
        // Capture the SAME accepted position shown by the main navigator
        // (GPS or fresh direct network/Wi-Fi) once, before the GPS-free DR
        // test begins. Previous code required an internal GPS fix and could
        // ignore the already available State.navFix when GPS was unavailable.
        val realGps=gps?.takeIf{
            !gpsQuarantined && now-gpsTime in 0L..10_000L
        }
        val network=State.networkFix?.takeIf{
            it.source=="Wi-Fi/сеть" &&
                now-it.elapsedMs in 0L..12_000L &&
                it.accuracy in 1f..220f &&
                !GpsSpoofGuard.isLima(it.lat,it.lon)
        }
        val navigator=State.navFix?.takeIf{
            now-it.elapsedMs in 0L..12_000L &&
                it.accuracy in 1f..250f &&
                it.lat in -85.0..85.0 && it.lon in -180.0..180.0 &&
                !GpsSpoofGuard.isLima(it.lat,it.lon) &&
                (it.source=="Wi-Fi/сеть" ||
                    (!gpsQuarantined &&
                        (it.source=="GPS" || it.source=="Fused Android")))
        }
        // In quarantine never seed from an old GPS or a fused fix which
        // may itself incorporate GNSS. Network provider is independent.
        val live=if(gpsQuarantined)network
            else navigator ?: realGps?.let{
                State.NavFix(it.latitude,it.longitude,
                    if(it.hasBearing())it.bearing.toDouble() else null,
                    it.accuracy,"GPS",gpsTime)
            } ?: network
        val manual=State.manualStart
        val lat=live?.lat?:manual?.lat
        val lon=live?.lon?:manual?.lon
        val heading=realGps?.takeIf{
            live?.source=="GPS" && it.hasBearing() &&
                it.hasSpeed() && it.speed>=2.0f
        }?.bearing?.toDouble()
            ?: lastTrustedHeading?.takeIf{
                now-lastTrustedHeadingMs in 0L..60_000L
            }
            ?: live?.bearing?.takeIf{
                it.isFinite() && (live.source=="GPS" ||
                    live.source=="Fused Android") &&
                    (correctedSpeed()?:0.0)>=5.0
            }
            ?: motion.compassBearing
            ?: manual?.bearing
        if(lat==null || lon==null){
            State.diagnosticText="Ожидаю текущие координаты GPS или Wi-Fi/сети. "+
                "В навигаторе позиция должна обновляться; "+
                "можно также задать начальную точку вручную."
            State.message=State.diagnosticText
            return
        }
        if(heading==null || !heading.isFinite()){
            State.diagnosticText="Начальные координаты найдены, "+
                "но направление автомобиля неизвестно. "+
                "Нужен GPS-курс во время движения, калиброванный компас "+
                "или вручную заданный курс."
            State.message=State.diagnosticText
            return
        }
        comparisonDr.start(lat,lon,heading,now)
        learnedDr.resetSegment()
        State.startDiagnostic()
        State.addNetworkDrPoint(lat,lon,heading,correctedSpeed()?:0.0,now)
        State.diagnosticNetworkStatus=comparisonDr.lastNetworkStatus
        State.message="Два трека запущены от "+
            (live?.source?:"ручной точки")+
            ". GPS теперь не используется для синего DR-трека."
    }

    private fun quarantineGps(now:Long,reason:String){
        // Break supervised pairs whenever a GPS sample is quarantined:
        // learning must never bridge across a spoofing episode.
        learnedDr.resetSegment()
        State.recentTrustedGps=null
        gpsQuarantined=true
        gpsConsensus.clear()
        quarantineRecoveryCount=0
        quarantineRecoveryLast=0L
        quarantineRecoverySince=0L
        quarantineCandidate=null
        gpsRecoveryCount=0
        gpsRecoveryLast=0L
        gpsRecoverySince=0L
        // A cached Fused fix might contain the same GNSS spoof. Discard it,
        // and use only independently reported NETWORK_PROVIDER fixes.
        if(State.networkFix?.source!="Wi-Fi/сеть")State.networkFix=null
        if(previousNetworkFix?.source!="Wi-Fi/сеть")previousNetworkFix=null
        networkCorrectionSamples=0
        lastGpsTeleport=now
        lastRejectedGps=now
        State.gpsText=reason
        State.gpsSpoofStatus="GPS отклонён · "+reason+
            " · проверяю восстановление по независимой позиции"
        // A suspicious GPS fix never seeds DR. Only an earlier accepted
        // GPS/manual position or corroborated independent NETWORK fix can.
        if(!State.dr && autoEnabled)startDr(force=false)
    }

    private fun resetQuarantineEvidence(message:String){
        quarantineRecoveryCount=0
        quarantineRecoveryLast=0L
        quarantineRecoverySince=0L
        quarantineCandidate=null
        gpsConsensus.clear()
        State.gpsSpoofStatus=message
    }

    private fun correctDrWithTrustedGps(
        location:Location,kmh:Double?,now:Long,
        positionBlend:Double,headingStep:Double
    ){
        if(!State.dr || dr.pose==null || lastGpsCorrectionMs>0L &&
            now-lastGpsCorrectionMs<2500L)return
        dr.correctPosition(location.latitude,location.longitude,positionBlend)
        val headingValid=location.hasBearing() && kmh!=null &&
            kmh>=12.0 && !reverse && abs(State.yawDegPerSec)<8.0 &&
            (!location.hasBearingAccuracy() ||
                location.bearingAccuracyDegrees<22f)
        if(headingValid)dr.correctHeading(location.bearing.toDouble(),headingStep)
        lastGpsCorrectionMs=now
        // Correction is deliberately fractional; never jump to one GPS fix.
        State.driftMeters=(State.driftMeters*
            if(positionBlend<0.08)0.94 else 0.88).coerceAtLeast(0.0)
    }

    /**
     * While spoofing quarantine is active, occasional good fixes may provide
     * cautious DR corrections. They MUST NOT set the trusted GPS anchor or
     * restore GPS navigation until a much longer continuous healthy window.
     */
    private fun allowGpsRecovery(location:Location,kmh:Double?,now:Long):Boolean{
        if(!gpsQuarantined)return true
        if(kmh==null || kmh !in 0.0..180.0 ||
            GpsSpoofGuard.isLima(location.latitude,location.longitude)){
            resetQuarantineEvidence(
                "GPS в карантине · скорость/координаты подозрительны")
            return false
        }
        // Fused may include spoofed GNSS: only direct NETWORK_PROVIDER
        // observations, or the DR pose propagated independently by OBD
        // and gyro, can corroborate a quarantined GPS sample.
        val network=State.networkFix?.takeIf{
            it.source=="Wi-Fi/сеть" && now-it.elapsedMs<15_000L &&
                it.accuracy in 1f..100f
        }
        val pose=dr.pose?.takeIf{State.dr}
        if(pose==null && network==null){
            resetQuarantineEvidence("GPS в карантине · нет независимого ориентира")
            return false
        }
        if(pose!=null){
            val independent=Location(LocationManager.NETWORK_PROVIDER).apply{
                latitude=pose.lat;longitude=pose.lon
            }
            val distance=independent.distanceTo(location).toDouble()
            val tolerance=(65.0+State.driftMeters*1.5).coerceIn(65.0,250.0)
            if(distance>tolerance){
                resetQuarantineEvidence(
                    "GPS в карантине · от DR: ${distance.toInt()} м")
                return false
            }
        }
        if(network!=null){
            val independent=Location(LocationManager.NETWORK_PROVIDER).apply{
                latitude=network.lat;longitude=network.lon
            }
            val distance=independent.distanceTo(location).toDouble()
            val tolerance=(network.accuracy*2.0+75.0).coerceIn(85.0,250.0)
            if(distance>tolerance){
                resetQuarantineEvidence(
                    "GPS в карантине · от Wi-Fi/сети: ${distance.toInt()} м")
                return false
            }
        }
        // Check successive GPS candidates against realistic vehicle travel,
        // not merely each one against the same last trusted fix.
        val prev=quarantineCandidate
        if(prev!=null && quarantineRecoveryLast>0L){
            val dt=(now-quarantineRecoveryLast)/1000.0
            if(dt>0.0 && dt<=gpsRecoveryMaxGapMs/1000.0){
                val maxTravel=(kmh/3.6)*dt+
                    location.accuracy*2.0+85.0
                if(prev.distanceTo(location)>maxTravel){
                    resetQuarantineEvidence(
                        "GPS в карантине · кандидаты не согласуются")
                    return false
                }
            }
        }
        if(quarantineRecoveryLast==0L ||
            now-quarantineRecoveryLast>gpsRecoveryMaxGapMs){
            quarantineRecoveryCount=0
            quarantineRecoverySince=now
        }
        if(quarantineRecoveryLast==0L ||
            now-quarantineRecoveryLast>=750L){
            quarantineRecoveryCount++
            quarantineRecoveryLast=now
            quarantineCandidate=Location(location)
        }
        if(quarantineRecoveryCount>=gpsCorrectionFixes){
            // Safe-looking short GPS window: use ONLY a tiny soft correction.
            // Never release quarantine solely because of these fixes.
            // Combine only independently corroborated, accepted fixes.
            val recovered=gpsConsensus.add(location,now)
            correctDrWithTrustedGps(recovered,kmh,now,0.06,0.35)
            State.markRawGpsApproved(now)
        }
        val stableMs=(now-quarantineRecoverySince).coerceAtLeast(0L)
        if(quarantineRecoveryCount>=gpsReturnFixes &&
            stableMs>=gpsReturnStableMs){
            gpsQuarantined=false
            gpsRecoveryCount=quarantineRecoveryCount
            gpsRecoveryLast=now
            gpsRecoverySince=quarantineRecoverySince
            quarantineCandidate=null
            quarantineRecoveryCount=0
            quarantineRecoveryLast=0L
            quarantineRecoverySince=0L
            lastGpsTeleport=0L
            State.gpsSpoofStatus=
                "GPS восстановлен: стабильность ${stableMs/1000} с; можно вернуть навигацию"
            return true
        }
        State.gpsSpoofStatus=
            "GPS карантин · проверено ${quarantineRecoveryCount} точек за "+
            "${stableMs/1000} с; DR корректируется, GPS не включён"
        return false
    }

    private fun acceptNetworkLocation(location:Location,provider:String) {
        // Preserve even kilometre-accuracy network fixes for the startup
        // accuracy circle. This never bypasses DR/spoof safety gates below.
        ApproximateFixStore.offer(location,provider)
        // Android Location.isMock prevents feeding our injected position
        // back into the filter as an apparently independent measurement.
        if(location.isMock || !location.hasAccuracy())return
        if(GpsSpoofGuard.isLima(location.latitude,location.longitude))return
        val age=SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos
        if(age<0L || age>12_000_000_000L)return
        if(location.latitude !in -85.0..85.0 ||
            location.longitude !in -180.0..180.0)return
        val now=SystemClock.elapsedRealtime()

        // Independent GPS-cut test intentionally sees direct network fixes
        // before the MAIN navigator's more restrictive accuracy threshold.
        // The comparison estimator itself validates network age, quality,
        // and that location.provider is exactly NETWORK_PROVIDER.
        if(State.diagnosticRunning){
            comparisonDr.onIndependentNetworkLocation(location,provider,now)
            State.diagnosticNetworkStatus=comparisonDr.lastNetworkStatus
        }
        if(gpsQuarantined && provider!="Wi-Fi/сеть")return
        val maxAccuracy=if(State.dr)180f else 220f
        if(location.accuracy<=0f || location.accuracy>maxAccuracy)return
        val candidate=State.NavFix(location.latitude,location.longitude,
            if(location.hasBearing())location.bearing.toDouble() else null,
            location.accuracy,provider,now)
        if(State.dr) {
            val pose=dr.pose?:return
            val estimated=Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude=pose.lat
                longitude=pose.lon
            }
            val distance=estimated.distanceTo(location).toDouble()
            // A cellular geolocation can be kilometres off; corrections
            // must stay near the predicted inertial path.
            val tolerance=max(65.0,
                min(220.0,location.accuracy.toDouble()*2.1+
                    State.driftMeters*1.3))
            if(distance>tolerance) {
                networkCorrectionSamples=0
                return
            }
            val previous=previousNetworkFix
            val consistent=previous!=null &&
                now-previous.elapsedMs in 1000L..15_000L &&
                run {
                    val prior=Location(LocationManager.NETWORK_PROVIDER).apply {
                        latitude=previous.lat;longitude=previous.lon
                    }
                    val elapsedSeconds=(now-previous.elapsedMs)/1000.0
                    val maxMove= max(75.0,
                        (correctedSpeed()?:0.0)/3.6*elapsedSeconds+
                        previous.accuracy+location.accuracy+55.0)
                    prior.distanceTo(location)<maxMove
                }
            networkCorrectionSamples=if(consistent)
                (networkCorrectionSamples+1).coerceAtMost(5) else 1
            previousNetworkFix=candidate
            State.networkFix=candidate
            if(networkCorrectionSamples>=2 &&
                now-lastNetworkCorrectionMs>=5000L) {
                dr.correctPosition(location.latitude,location.longitude,
                    if(location.accuracy<=70f)0.07 else 0.02)
                State.driftMeters=(State.driftMeters*.94).coerceAtLeast(0.0)
                lastNetworkCorrectionMs=now
                lastCorrectionSource=provider
                State.message="Мягкая коррекция DR: "+provider+
                    ", точность ~"+location.accuracy.toInt()+" м"
            }
            return
        }

        val gpsRecent=!gpsQuarantined && gps!=null && now-gpsTime<8000L
        if(gpsRecent){
            // Only the direct Android NETWORK_PROVIDER is independent of
            // GNSS. Retain plausible Wi-Fi/cellular fixes as a weak
            // correction, without mutating the validated GPS anchor.
            if(provider=="Wi-Fi/сеть" && location.accuracy in 1f..120f){
                val gap=gps!!.distanceTo(location).toDouble()
                if(gap<=max(65.0,location.accuracy*1.8)){
                    State.networkFix=candidate
                    previousNetworkFix=candidate
                }
            }
            return
        }
        val anchor=gps
        if(anchor!=null) {
            val dt=(now-gpsTime).coerceAtLeast(0L)/1000.0
            val maxMove=350.0+min(dt,600.0)*36.0
            if(anchor.distanceTo(location)>maxMove)return
        }
        val manual=State.manualStart
        if(manual!=null && anchor==null) {
            val start=Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude=manual.lat;longitude=manual.lon
            }
            if(start.distanceTo(location)>max(350.0,location.accuracy*4.0))return
        }
        previousNetworkFix=candidate
        State.networkFix=candidate
        State.navFix=candidate
        State.sourceDescription="Сеть: "+provider+
            "; точность ~"+location.accuracy.toInt()+" м"
    }

    private fun publishActualNavigationPosition(now:Long) {
        if(State.dr) {
            dr.pose?.let{ pose ->
                State.navFix=State.NavFix(pose.lat,pose.lon,
                    (pose.bearing+if(reverse)180.0 else 0.0)%360.0,
                    (15+State.driftMeters).coerceAtMost(250.0).toFloat(),
                    "OBD + инерциальные датчики",now)
                State.sourceDescription=when {
                    now-lastGpsCorrectionMs<25_000L && lastGpsCorrectionMs>0L ->
                        "DR + проверенная GPS-коррекция"
                    now-lastNetworkCorrectionMs<25_000L && lastNetworkCorrectionMs>0L ->
                        "DR + коррекция "+lastCorrectionSource
                    else -> "Инерциальный расчёт · без коррекции"
                }
            }
            return
        }
        val currentGps=if(gpsQuarantined)null else gps
        if(currentGps!=null && now-gpsTime<7500L) {
            var latitude=currentGps.latitude
            var longitude=currentGps.longitude
            var adjusted=false
            val network=State.networkFix?.takeIf {
                it.source=="Wi-Fi/сеть" && it.accuracy in 1f..120f &&
                    now-it.elapsedMs in 0L..10_000L
            }
            if(network!=null) {
                val sample=Location(LocationManager.NETWORK_PROVIDER).apply{
                    this.latitude=network.lat
                    this.longitude=network.lon
                }
                val gap=currentGps.distanceTo(sample).toDouble()
                if(gap in 0.5..max(65.0,network.accuracy*1.8)) {
                    // Cap the correction to 4 metres from the independently
                    // accepted GPS location. No system location injection.
                    val alpha=min(0.05,4.0/gap)
                    latitude+=(network.lat-latitude)*alpha
                    longitude+=(network.lon-longitude)*alpha
                    adjusted=true
                }
            }
            State.navFix=State.NavFix(latitude,longitude,
                if(currentGps.hasBearing())currentGps.bearing.toDouble() else null,
                currentGps.accuracy,"GPS",now)
            State.sourceDescription=(if(BuildConfig.FYT7870_HEAD_UNIT)
                State.fytGpsActiveSource else "GPS")+
                (if(adjusted)" + Wi-Fi/сеть" else "")+
                " · точность ~${currentGps.accuracy.toInt()} м"
            return
        }
        val net=State.networkFix?.takeIf{
            !gpsQuarantined || it.source=="Wi-Fi/сеть"
        }
        if(net!=null && now-net.elapsedMs<12_000L) {
            State.navFix=net
            State.sourceDescription=net.source+" · ~${net.accuracy.toInt()} м"
            return
        }
        val saved=State.manualStart
        if(saved!=null && !State.dr) {
            State.navFix=State.NavFix(saved.lat,saved.lon,saved.bearing,
                30f,"Ручная точка",now)
            State.sourceDescription="Ручная стартовая точка · GPS нет"
            return
        }
        State.navFix=null
        State.sourceDescription="Нет свежих GPS/Wi-Fi/сотовых данных"
    }

    private fun correctedSpeed(): Double? = State.obdSpeedKmh()

    private fun applyDetectedDirection(reverseDetected: Boolean, method: String) {
        if(State.directionConfirmed && reverse==reverseDetected) return
        reverse=reverseDetected
        State.reverseSelected=reverseDetected
        State.directionConfirmed=true
        State.directionText=(if(reverseDetected) "Назад" else "Вперёд")+" ($method)"
        State.message="Направление определено: "+State.directionText
    }

    private fun updateNavigation(now: Long) {
        val freshObd = State.speed != null && now-speedTime < 3500L
        val obdKmh = if(freshObd) correctedSpeed() ?: 0.0 else 0.0
        if(freshObd) motion.directionFromAcceleration(obdKmh,now)?.let {
            applyDetectedDirection(it,"акселерометр")
        }
        val gpsRecent = !gpsQuarantined && gps != null && now-gpsTime < 6000L
        val gpsSpeedRecent = State.gpsSpeedKmh != null &&
            now-State.gpsSpeedUpdatedElapsedMs < 6000L
        val gpsMoving=gpsRecent &&
            ((gpsSpeedRecent && State.gpsSpeedKmh!!>2.5) ||
                (BuildConfig.FYT7870_HEAD_UNIT &&
                 acceptedExternalReceiver && freshObd && obdKmh>2.5))
        val recentTeleport=lastGpsTeleport>0L &&
            now-lastGpsTeleport<20_000L
        val gpsSpeedUnavailable=(!gpsSpeedRecent ||
            State.gpsSpeedKmh==null || State.gpsSpeedKmh!!<1.5) &&
            !(BuildConfig.FYT7870_HEAD_UNIT && acceptedExternalReceiver &&
              freshObd && gpsRecent)

        // During an active route, missing GNSS + real OBD motion should
        // switch to DR even when NO air alert is active. The previous
        // alert-only condition left Xiaomi 13T stopped in GPS mode.
        val recentInvalidGps=gpsQuarantined || !gpsRecent ||
            gpsSpeedUnavailable || recentTeleport ||
            (gpsSpeedRecent && !gpsMoving)
        val eligible=MobileDrAutoStart.eligible(
            autoEnabled=autoEnabled,alreadyDr=State.dr,
            routeActive=State.activeRouting,
            alertActive=AirAlerts.autoNavigationAlertActive(),
            freshObd=freshObd,obdKmh=obdKmh,
            badGps=recentInvalidGps
        )
        if(eligible){
            if(gpsInvalidSince==0L)gpsInvalidSince=now
            if(now-gpsInvalidSince>=MobileDrAutoStart.debounceMs(
                    recentTeleport))startDr(force=false)
        }else if(!State.dr)gpsInvalidSince=0L
        if(gpsQuarantined && !State.dr && autoEnabled &&
            freshObd && obdKmh>=0.0)startDr(force=false)

        if(State.dr) {
            // Recovery requires consecutive real GNSS fixes; discard old evidence.
            if(gpsRecoveryLast > 0L &&
                now-gpsRecoveryLast > gpsRecoveryMaxGapMs){
                gpsRecoveryCount=0
                gpsRecoverySince=0L
            }
            if(!freshObd) {
                State.message="Инерциальное ведение: нет свежей скорости OBD"
                State.navigationMode="DR: нет OBD"
                tickTime=now
                return
            }
            if(!State.directionConfirmed && obdKmh>=2.0) {
                // A known DR seed course and gyro already define travel
                // direction. Forward is the normal OBD convention; reverse
                // remains explicitly selectable. Do not freeze a good
                // calibrated DR merely for lack of 3 fresh GPS courses.
                if(dr.pose!=null && !reverse)
                    applyDetectedDirection(false,"курс старта; вперёд по умолчанию")
                else {
                    State.navigationMode="DR: направление не определено"
                    State.message="Укажи направление движения: Вперёд / Назад"
                    tickTime=now
                    return
                }
            }
            val dt=((now-tickTime)/1000.0).coerceIn(0.0,0.45)
            if(dt > 0) {
                val gain=if(State.learnedDrEnabled)
                    learnedDr.speedGain(obdKmh,State.yawDegPerSec)
                    else 1.0
                dr.move((obdKmh*gain).coerceIn(0.0,260.0),dt,reverse)
                State.driftMeters += obdKmh/3.6*dt*.045 + .12*dt
                // Magnetic readings can be distorted by steel and electronics:
                // apply only a very small bounded correction, never a snap.
                motion.compassBearing?.let { heading ->
                    dr.correctHeading(heading,0.18)
                }
            }
            dr.pose?.let { pose ->
                State.position="%.6f, %.6f; %.0f°".format(
                    Locale.US,pose.lat,pose.lon,pose.bearing)
                State.recordInertialPoint(pose.lat,pose.lon,
                    (pose.bearing+if(reverse)180.0 else 0.0)%360.0,
                    obdKmh,now)
                State.navigationMode=if(manualMode) "Датчики (вручную)" else "Датчики (авто)"
            }
        } else {
            State.navigationMode="GPS"
            if(gpsRecent) {
                State.position="%.6f, %.6f".format(Locale.US,gps!!.latitude,gps!!.longitude)
            }
        }
    }

    private fun startDr(force:Boolean) {
        if(State.dr) {
            if(force) {
                manualMode=true
                State.manualForced=true
                State.navigationMode="Датчики (вручную)"
            }
            return
        }
        val now=SystemClock.elapsedRealtime()
        if(State.speed==null || now-speedTime>3500L) {
            State.message="Нет свежей скорости OBD: проверь адаптер"
            return
        }
        // Accepted live GNSS is best, then independent Wi-Fi/cellular,
        // then a deliberate manual start; a route start is the LAST
        // resort when guidance is already active and GPS is unavailable.
        // Never seed from stale or quarantined/spoofed GNSS.
        val network=State.networkFix?.takeIf{
            now-it.elapsedMs in 0L..15_000L &&
                it.accuracy in 1f..180f &&
                (!gpsQuarantined || it.source=="Wi-Fi/сеть")
        }
        val gpsCurrent=gps?.takeIf{
            !gpsQuarantined && now-gpsTime in 0L..30_000L
        }
        val manual=State.manualStart?.takeIf {
            force || (gpsCurrent==null && network==null)
        }
        val routeSeed=State.routeDrSeed?.takeIf{
            State.activeRouting && gpsCurrent==null &&
                network==null && manual==null &&
                it.lat in -85.0..85.0 && it.lon in -180.0..180.0 &&
                it.bearing?.isFinite()==true
        }
        val fix=when{
            manual!=null -> Location(LocationManager.GPS_PROVIDER).apply{
                latitude=manual.lat;longitude=manual.lon
                bearing=manual.bearing.toFloat()
                accuracy=25f
                speed=((correctedSpeed()?:0.0)/3.6).toFloat()
            }
            gpsCurrent!=null -> Location(gpsCurrent)
            network!=null -> Location(LocationManager.NETWORK_PROVIDER).apply{
                latitude=network.lat;longitude=network.lon
                accuracy=network.accuracy
                network.bearing?.let{bearing=it.toFloat()}
            }
            routeSeed!=null -> Location("route-start").apply {
                latitude=routeSeed.lat;longitude=routeSeed.lon
                bearing=routeSeed.bearing!!.toFloat()
                accuracy=routeSeed.accuracy
            }
            else->null
        }
        if(fix==null) {
            State.message="Нет исходной позиции: задай старт на карте или дождись GPS/сети"
            return
        }
        val trustedCourse=lastTrustedHeading?.takeIf {
            now-lastTrustedHeadingMs in 0L..60_000L && !gpsQuarantined
        }
        val locationCourse=fix.bearing.toDouble().takeIf {
            fix.hasBearing() && fix.hasSpeed() && fix.speed*3.6f>7f
        }
        // Road direction is a last-resort bearing when no aligned
        // compass/GPS course exists, including when NETWORK supplies a
        // geographical fix but no course. It never replaces fresh GNSS.
        val routeCourse=State.routeDrSeed?.takeIf{
            State.activeRouting && it.bearing?.isFinite()==true
        }?.bearing
        val course=when {
            manual!=null -> manual.bearing
            locationCourse!=null -> locationCourse
            trustedCourse!=null -> trustedCourse
            motion.compassBearing!=null -> motion.compassBearing!!
            routeCourse!=null -> routeCourse
            else -> Double.NaN
        }
        if(!course.isFinite()) {
            State.message="Курс неизвестен: задай старт и направление на карте"
            return
        }
        if(!State.directionConfirmed) {
            // Normal OBD-II speed is unsigned: without evidence of reverse,
            // use the seeded forward course, rather than block DR until
            // GPS returns. Explicit REVERSE/FORWARD actions remain available.
            reverse=false
            applyDetectedDirection(false,when {
                manual!=null->"курс задан вручную"
                motion.compassBearing!=null->"привязанный компас"
                routeCourse!=null->"курс маршрута (без GPS)"
                else->"последний проверенный курс"
            })
        }
        val carBearing=(course+if(reverse)180.0 else 0.0)%360.0
        dr.seed(fix.latitude,fix.longitude,carBearing)
        State.clearInertialTrail()
        lastDrStart=now
        State.recordInertialPoint(fix.latitude,fix.longitude,course,
            correctedSpeed()?:0.0,now)
        tickTime=now
        gpsRecoveryCount=0
        gpsRecoveryLast=0L
        gpsRecoverySince=0L
        State.driftMeters=0.0
        networkCorrectionSamples=0
        previousNetworkFix=null
        lastNetworkCorrectionMs=0L
        lastGpsCorrectionMs=0L
        manualMode=force
        State.manualForced=force
        State.dr=true
        State.navigationMode=if(force)"Датчики (вручную)" else "Датчики (авто)"
        State.message=when {
            manual!=null->"DR: старт и курс взяты с карты"
            routeSeed!=null->"DR: старт с маршрута; курс приблизительный, GPS нет"
            else->"Инерциальное ведение включено: OBD + гироскоп + компас"
        }
    }
    private fun stopDr() {
        State.dr=false
        State.manualForced=false
        manualMode=false
        gpsRecoveryCount=0
        gpsRecoveryLast=0L
        gpsRecoverySince=0L
        gpsInvalidSince=0L
        lastGpsTeleport=0L
        networkCorrectionSamples=0
        State.driftMeters=0.0
        State.navigationMode="GPS"
        State.message="Ведение по GPS (инерциальный режим выключен)"
    }

    override fun onLocationChanged(location: Location) {
        if(location.provider!=LocationManager.GPS_PROVIDER)return
        // On FYT the GM906BT driver injects external GNSS through a third-
        // party mock-location provider. Do not inject any location ourselves.
        val external=BuildConfig.FYT7870_HEAD_UNIT && location.isMock
        if(location.isMock && !BuildConfig.FYT7870_HEAD_UNIT)return
        val now=SystemClock.elapsedRealtime()
        val ageNanos=SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos
        if(ageNanos<0L || ageNanos>8_000_000_000L)return
        if(BuildConfig.FYT7870_HEAD_UNIT) {
            if(external)State.fytExternalGpsMs=now
            else State.fytInternalGpsMs=now
            // A fresh accepted GM906BT stream wins over the internal GNSS.
            // The internal receiver remains available after signal timeout.
            if(!FytGpsPriority.process(external,lastExternalAcceptedMs,now))
                return
        }
        if(location.latitude !in -85.0..85.0 ||
            location.longitude !in -180.0..180.0)return
        State.addRawGps(location.latitude,location.longitude,now)
        // Filter individual Android-computed GNSS fixes. These are NOT
        // per-satellite coordinates; satellite-level elimination is not
        // possible using LocationManager alone.
        val reportedSpeedKmh=if(location.hasSpeed())
            location.speed.toDouble()*3.6 else null
        if(reportedSpeedKmh!=null &&
            (!reportedSpeedKmh.isFinite() ||
                reportedSpeedKmh<0.0 ||
                reportedSpeedKmh>GpsFixConsensus.MAX_GPS_SPEED_KMH)){
            quarantineGps(now,"GPS: скорость >180 км/ч · точка отклонена")
            return
        }
        // Route checks use the active planned route created from TRUSTED
        // navigation coordinates, not a spoofed GPS location. Without a
        // route, the existing OBD/DR plausibility gates remain in force.
        val road=State.gpsFilterRoutePoints.takeIf{
            State.activeRouting && it.size>=2
        }
        if(road!=null){
            val deviation=GpsFixConsensus.routeDistanceMeters(
                location.latitude,location.longitude,road)
            if(deviation.isFinite() &&
                deviation>GpsFixConsensus.MAX_ROUTE_DEVIATION_METERS){
                quarantineGps(now,"GPS: отклонение от маршрута "+
                    "${deviation.toInt()} м (>500 м) · точка отклонена")
                return
            }
        }
        // This gross geographic anomaly must be rejected even if GNSS
        // advertises poor accuracy or a fabricated low velocity.
        if(GpsSpoofGuard.isLima(location.latitude,location.longitude)){
            val reported=if(location.hasSpeed())location.speed*3.6 else null
            quarantineGps(now,"GPS: координаты в районе Лимы"+
                (if(reported!=null && reported>180.0)
                    " · скорость >180 км/ч" else ""))
            return
        }
        if(!location.hasAccuracy() || location.accuracy>50f) {
            State.gpsText="GPS: низкая точность"
            if(gpsQuarantined)resetQuarantineEvidence(
                "GPS в карантине · недостаточная точность")
            return
        }
        val kmh=if(location.hasSpeed()) location.speed*3.6 else null
        if(kmh!=null && kmh in 0.0..350.0) {
            State.gpsSpeedKmh=kmh
            State.gpsSpeedUpdatedElapsedMs=now
        } else {
            State.gpsSpeedKmh=null
            State.gpsSpeedUpdatedElapsedMs=0L
        }
        val freshObd=State.speed!=null && now-speedTime<3500L
        val obd=if(freshObd)correctedSpeed()?:0.0 else 0.0
        // A sudden >1 km teleport cannot become our new GNSS anchor.
        // Compare against plausible travel over elapsed time so a normal
        // long drive between sparse fixes isn't misclassified.
        gps?.let { previous ->
            val dt=(now-gpsTime)/1000.0
            if(dt in 0.2..90.0) {
                val jump=previous.distanceTo(location).toDouble()
                val plausible=max(obd,kmh?:0.0)/3.6*dt+
                    location.accuracy*2.5+250.0
                if(jump>1000.0 && jump>plausible) {
                    quarantineGps(now,"GPS: невозможный скачок >1 км")
                    return
                }
            }
        }

        // Speed disagreement during OBD-confirmed motion is an important
        // signal of spoofing/jamming (but not proof by itself).
        if(freshObd && obd>=7) {
            // Some external NMEA mock providers omit the Android speed field.
            // Permit position-only GM906BT fixes if Vgate reports movement.
            // The OBD speed never masquerades as GPS speed in the HUD.
            if((kmh==null && !external) || (kmh!=null && kmh<1.5)) {
                State.gpsText="GPS: скорость 0/неизвестна, OBD движется"
                if(gpsQuarantined)resetQuarantineEvidence(State.gpsText)
                return
            }
            if(abs(kmh-obd)>max(18.0,obd*.50)) {
                State.gpsText="GPS: скорость расходится с OBD"
                if(gpsQuarantined)resetQuarantineEvidence(State.gpsText)
                return
            }
        }

        // Reject physically implausible teleports from the last trusted fix.
        gps?.let { previous ->
            val dt=(now-gpsTime)/1000.0
            if(dt>0.0 && dt<90.0) {
                val maxDistance=max(65.0, max(obd,
                    kmh?:0.0)/3.6*dt + location.accuracy*2.5 + 35.0)
                if(previous.distanceTo(location)>maxDistance) {
                    State.gpsText="GPS: скачок координат отклонён"
                    lastRejectedGps=now
                    if(gpsQuarantined)resetQuarantineEvidence(State.gpsText)
                    return
                }
            }
        }
        if(State.dr) {
            val pose=dr.pose ?: return
            val predicted=Location(LocationManager.GPS_PROVIDER).apply {
                latitude=pose.lat
                longitude=pose.lon
            }
            val acceptable=max(65.0,
                location.accuracy*1.8+State.driftMeters*1.5).coerceAtMost(220.0)
            if(predicted.distanceTo(location)>acceptable) {
                State.gpsText="GPS: слишком далеко от инерциального пути"
                if(gpsQuarantined)resetQuarantineEvidence(State.gpsText)
                return
            }
        }
        // Valid-looking fixes alone never release quarantine. First compare
        // multiple samples against the independent network/DR anchor.
        if(gpsQuarantined && !allowGpsRecovery(
                location,kmh?:if(external && freshObd)obd else null,now)){
            State.gpsText=State.gpsSpoofStatus
            return
        }
        // The GNSS position is derived only from retained validated
        // samples. A robust motion-compensated rolling estimate removes
        // remaining isolated outliers without trusting a single GPS fix.
        if(BuildConfig.FYT7870_HEAD_UNIT &&
            gps!=null && acceptedExternalReceiver!=external) {
            // Don't mix physical-FYT and external GM906BT samples in
            // one averaging window when selecting a different receiver.
            gpsConsensus.clear()
        }
        val acceptedPosition=gpsConsensus.add(location,now)
        if(BuildConfig.FYT7870_HEAD_UNIT){
            acceptedExternalReceiver=external
            if(external)lastExternalAcceptedMs=now
            State.fytGpsActiveSource=if(external)
                "GM906BT / внешний Mock GPS"
                else "Внутренний GPS FYT"
        }
        gps=Location(acceptedPosition)
        gpsTime=now
        State.recentTrustedGps=State.NavFix(acceptedPosition.latitude,
            acceptedPosition.longitude,
            if(acceptedPosition.hasBearing())acceptedPosition.bearing.toDouble() else null,
            acceptedPosition.accuracy,"GPS",now)
        lastAcceptedGps=now
        State.markRawGpsApproved(now)
        // Learn ONLY from trusted GPS fixes in the explicit two-track
        // experiment. These measurements never enter the test DR estimator.
        if(State.diagnosticRunning && !gpsQuarantined &&
            State.speed!=null && now-speedTime<3500L) {
            learnedDr.observeTrustedGps(location,comparisonDr.pose,
                correctedSpeed(),comparisonDr.correctionCount,
                gpsConsensus.sampleCount,now)
            State.learnedDrStatus=learnedDr.status()
            State.learnedDrDetail=learnedDr.detail()
        }
        State.gpsText=(if(BuildConfig.FYT7870_HEAD_UNIT)
            State.fytGpsActiveSource+": " else "GPS: ")+
            "принятые координаты, усреднение "+
            "${gpsConsensus.sampleCount} точек; ~"+
            location.accuracy.toInt()+" м"
        if(!gpsQuarantined)
            State.gpsSpoofStatus="GPS: приняты только проверенные координаты"
        motion.setGeomagneticDeclination(location)

        if(location.hasBearing() && kmh!=null && kmh>=5.0 &&
            (!location.hasBearingAccuracy() ||
                location.bearingAccuracyDegrees<25f)) {
            lastTrustedHeading=location.bearing.toDouble()
            lastTrustedHeadingMs=now
        }

        val hasCourse=location.hasBearing() && kmh!=null && kmh>=12.0
        if(hasCourse) {
            val accuracyOk=!location.hasBearingAccuracy() ||
                location.bearingAccuracyDegrees<22f
            // A single valid-looking GPS course during DR must not re-align
            // the phone or change driving direction. Wait for corroborated
            // repeated samples, just as with the position correction.
            val confirmedCourse=accuracyOk &&
                (!State.dr || gpsRecoveryCount>=gpsCorrectionFixes)
            if(confirmedCourse)
                motion.directionFromGps(location.bearing.toDouble(),kmh!!,now)?.let {
                    applyDetectedDirection(it,"проверенный GPS + компас")
                }
            if(!reverse && abs(State.yawDegPerSec)<3.0 && confirmedCourse)
                motion.alignToGps(location.bearing.toDouble(),kmh!!,true)
        }

        if(State.dr) {
            // Position can be refined after three good samples. Switching
            // the primary source to GPS requires far stronger evidence:
            // >=18 samples sustained over >=30 seconds, without long gaps.
            if(gpsRecoveryLast>0L &&
                now-gpsRecoveryLast>gpsRecoveryMaxGapMs){
                gpsRecoveryCount=0
                gpsRecoverySince=0L
            }
            // Stationary GNSS is valid when OBD also reports stationary.
            // Rejected zero-speed GPS during genuine OBD motion never
            // reaches this accepted-GNSS branch.
            val motionConsistent=(
                (kmh!=null && kmh in 0.0..180.0 &&
                    (!freshObd || obd<2.5 || kmh>=2.5)) ||
                (external && freshObd && obd in 0.0..180.0)
            )
            if(motionConsistent &&
                (gpsRecoveryLast==0L || now-gpsRecoveryLast>=750L)){
                if(gpsRecoveryCount==0)gpsRecoverySince=now
                gpsRecoveryCount++
                gpsRecoveryLast=now
            }else if(!motionConsistent){
                gpsRecoveryCount=0
                gpsRecoverySince=0L
            }

            if(gpsRecoveryCount>=gpsCorrectionFixes){
                correctDrWithTrustedGps(acceptedPosition,kmh,now,0.08,0.4)
                State.gpsSpoofStatus="DR + мягкая GPS-коррекция; "+
                    "восстановление ${(now-gpsRecoverySince)/1000} с"
            }
            if(!manualMode && gpsRecoveryCount>=gpsReturnFixes &&
                gpsRecoverySince>0L &&
                now-gpsRecoverySince>=gpsReturnStableMs &&
                !gpsQuarantined){
                stopDr()
                State.message="GPS стабилен 5+ с: возврат к спутниковой навигации"
            }
        }
    }
    override fun onBind(i:Intent?):IBinder?=null
    override fun onDestroy() {
        handler.removeCallbacks(loop);stopScan();stopDr()
        disconnectTransport()
        motion.stop()
        comparisonDr.stop()
        learnedDr.save()
        State.stopDiagnostic()
        hybridLocator.stop()
        try {lm.removeUpdates(this)} catch(_:Exception){}
        // Active sensor readings must expire immediately when the app is
        // backgrounded. Retained Google route lives in PhoneRouteSession.
        State.speed=null
        State.speedUpdatedElapsedMs=0L
        State.gpsSpeedKmh=null
        State.gpsSpeedUpdatedElapsedMs=0L
        State.navFix=null
        State.networkFix=null
        State.recentTrustedGps=null
        State.fytGpsActiveSource="Мониторинг остановлен"
        super.onDestroy()
    }
}
