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
        const val STOP_DR="STOP_DR"
        const val EXIT="EXIT"
        const val SET_MUT="SET_MUT"
        const val SET_OBD="SET_OBD"
        const val AUTO_ON="AUTO_ON"
        const val AUTO_OFF="AUTO_OFF"
        const val CALIBRATE="CALIBRATE"
        const val ALIGN_COMPASS="ALIGN_COMPASS"
        const val REVERSE="REVERSE"
        const val FORWARD="FORWARD"
        const val MONITOR="MONITOR"
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lm: LocationManager
    private lateinit var motion: MotionSensors
    private lateinit var fused: FusedMockRelay
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var sppSocket: BluetoothSocket? = null
    private var sppOutput: OutputStream? = null
    private var sppReader: Thread? = null
    private var transport = "BLE"
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val bt by lazy { getSystemService(BluetoothManager::class.java).adapter }
    private val dr = DeadReckoner()
    private var gps: Location? = null
    private var gpsTime = 0L
    private var speedTime = 0L
    private var tickTime = 0L
    private var lastMockSent = 0L
    private var lastDrStart = 0L
    private var gpsInvalidSince = 0L
    private var gpsRecoveryCount = 0
    private var gpsRecoveryLast = 0L
    private var lastRejectedGps = 0L
    private var lastAcceptedGps = 0L
    private var manualMode = false
    private var reverse = false
    private var fusedStarting = false
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

    // Early Mitsubishi K-line. ELM327 handles 5-baud / fast initialization on the adapter.
    private val protocols = listOf(
        "3" to "ISO 9141-2",
        "4" to "KWP2000 5-baud",
        "5" to "KWP2000 fast",
        "0" to "Auto OBD2"
    )
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
            updateNavigation(now)
            tickTime=now
            handler.postDelayed(this,250)
        }
    }
    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        fused = FusedMockRelay(this)
        motion = MotionSensors(this) { yaw, dt ->
            if (State.dr) dr.turn(yaw, dt)
        }
        motion.start()
        State.navigationMode = "GPS"
        State.autoEnabled = true
        State.manualForced = false
        State.dr = false
        State.gpsText = "Ожидаем GPS"
        autoEnabled = true
        reverse = false
        val channel = NotificationChannel("nav","OBD GPS Navigator",NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val intent = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this,"nav").setContentTitle("OBD GPS Navigator").setContentText("GPS/OBD monitoring")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(intent).build()
        startForeground(1,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,this,Looper.getMainLooper()) } catch (_:Exception) {}
        tickTime=SystemClock.elapsedRealtime()
        handler.post(loop)
    }
    override fun onStartCommand(i: Intent?,flags:Int,startId:Int): Int {
        when(i?.action) {
            SCAN -> scan()
            CONNECT -> i.getStringExtra("address")?.let { connectChoice(it) }
            SET_MUT -> changeMode(true)
            SET_OBD -> changeMode(false)
            START_DR -> startDr(force=true)
            STOP_DR -> stopDr()
            AUTO_ON -> { autoEnabled=true; State.autoEnabled=true; State.message="Автоведение включено" }
            AUTO_OFF -> { autoEnabled=false; State.autoEnabled=false; State.message="Автоведение отключено" }
            CALIBRATE -> {
                val text=motion.calibrate(State.speed,SystemClock.elapsedRealtime()-speedTime<3000)
                State.message=text
                if(text.startsWith("Калибровка:")) handler.postDelayed({
                    State.message=motion.finishCalibration()
                },5000)
            }
            ALIGN_COMPASS -> {
                val location=gps
                State.message=if(location!=null && SystemClock.elapsedRealtime()-gpsTime<10000 &&
                    location.hasBearing() && location.speed*3.6>=12f)
                    motion.alignNow(location.bearing.toDouble()) else
                    "Для согласования компаса проедь прямо с хорошим GPS (более 12 км/ч)"
            }
            REVERSE -> { reverse=true;State.reverseSelected=true
                State.directionText="Назад (выбрано вручную)"
                State.message="Выбран задний ход вручную" }
            FORWARD -> { reverse=false;State.reverseSelected=false
                State.directionText="Вперёд (предположительно)"
                State.message="Выбрано движение вперёд" }
            MONITOR -> State.message="Мониторинг датчиков запущен"
            EXIT -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }
    private fun changeMode(useMut:Boolean) {
        mutMode=useMut
        State.mutMode=useMut
        State.modeLabel=if(useMut) "MUT-II 0x2F — эксперимент" else "OBD2 K-Line"
        // Restart protocol negotiation in the same Bluetooth session.
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
        State.message=if(useMut) "MUT-II: пробую 0x01/15625 бод" else "OBD2: поиск ISO9141/KWP"
    }
    private fun fallbackFromMut(reason:String) {
        changeMode(false)
        State.modeLabel="OBD2 K-Line (MUT недоступен)"
        State.message="MUT-II не поддержан: $reason; переключился на OBD2 K-Line"
    }
    private fun scan() {
        try {
            stopScan()
            State.devices.clear()
            // Show paired classic devices and BLE devices separately. Some
            // iCar Pro 2S variants advertise as Android-Vlink over RFCOMM.
            bt.bondedDevices.forEach { d ->
                val name=d.name ?: "Bluetooth без имени"
                if(d.type==BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                   d.type==BluetoothDevice.DEVICE_TYPE_DUAL ||
                   d.type==BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                    State.devices["SPP|${d.address}"]="Сопряжён: $name [Classic]"
                if(d.type==BluetoothDevice.DEVICE_TYPE_LE ||
                   d.type==BluetoothDevice.DEVICE_TYPE_DUAL)
                    State.devices["BLE|${d.address}"]="Сопряжён: $name [BLE]"
            }
            val scan=bt.bluetoothLeScanner
            if(scan==null) {
                State.message="Bluetooth выключен; доступны только сопряжённые устройства"
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
    private fun connectChoice(choice:String) {
        val protocol=choice.substringBefore("|")
        val address=choice.substringAfter("|",choice)
        if(protocol=="SPP") connectClassic(address) else connect(address)
    }
    private fun connectClassic(address:String) {
        stopScan()
        disconnectTransport()
        transport="SPP"
        State.deviceName=State.devices["SPP|$address"] ?: address
        changeMode(mutMode)
        State.message="Подключение Bluetooth Classic к $address"
        val socket=bt.getRemoteDevice(address).createRfcommSocketToServiceRecord(
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
        sppSocket=socket
        sppReader=Thread {
            try {
                bt.cancelDiscovery()
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
            State.deviceName=State.devices["BLE|$address"] ?: address
            gatt=bt.getRemoteDevice(address).connectGatt(this,false,object:BluetoothGattCallback(){
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
    private fun startDr() {
        val now=SystemClock.elapsedRealtime()
        if(State.speed==null || now-speedTime>3000) {State.message="Нет скорости ELM327";return}
        val p=gps
        if(p==null || now-gpsTime>15000 || !p.hasBearing() || p.accuracy>30f) {
            State.message="Нужен свежий GPS и направление (проедьте при хорошем сигнале)";return
        }
        try {
            lm.addTestProvider(LocationManager.GPS_PROVIDER,false,true,false,false,true,true,true,
                android.location.Criteria.POWER_LOW,android.location.Criteria.ACCURACY_FINE)
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
            mockOn=true
            dr.seed(p.latitude,p.longitude,p.bearing.toDouble())
            State.dr=true
            tickTime=now
            State.message="Dead Reckoning включён"
        } catch(e:Exception) {State.message="Выберите приложение фиктивных местоположений: ${e.message}"}
    }
    private fun stopDr() {
        State.dr=false
        if(mockOn) {
            try {lm.removeTestProvider(LocationManager.GPS_PROVIDER)} catch(_:Exception){}
            mockOn=false
        }
        gps=null; gpsTime=0
        State.message="DR выключен. Возврат к GPS."
    }
    override fun onLocationChanged(location:Location) {
        if(State.dr || location.isMock || !location.hasAccuracy() || location.accuracy>30f) return
        if(gps!=null && gps!!.distanceTo(location)>5000f) { State.message="GPS: резкий скачок отклонён";return }
        gps=Location(location);gpsTime=SystemClock.elapsedRealtime()
    }
    override fun onSensorChanged(event:SensorEvent) {
        if(event.sensor.type==Sensor.TYPE_GRAVITY) gravity=event.values.clone()
        if(event.sensor.type==Sensor.TYPE_GYROSCOPE && State.dr) {
            val previous=gyroTime;gyroTime=event.timestamp
            if(previous==0L) return
            val dt=(event.timestamp-previous)*1e-9
            if(dt<=0 || dt>0.5) return
            val norm=sqrt(gravity.map {it.toDouble()*it}.sum())
            if(norm<7) return
            val yaw=event.values.indices.sumOf {event.values[it].toDouble()*gravity[it]}/norm
            if(State.speed==0) yawBias=.995*yawBias+.005*yaw
            dr.turn(yaw-yawBias,dt)
        }
    }
    override fun onAccuracyChanged(s:Sensor?,accuracy:Int) {}
    override fun onBind(i:Intent?):IBinder?=null
    override fun onDestroy() {
        handler.removeCallbacks(loop);stopScan();stopDr()
        disconnectTransport()
        sm.unregisterListener(this)
        try {lm.removeUpdates(this)} catch(_:Exception){}
        super.onDestroy()
    }
}
