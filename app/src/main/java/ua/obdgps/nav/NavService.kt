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
import kotlin.math.*

@SuppressLint("MissingPermission")
class NavService: Service(), SensorEventListener, LocationListener {
    companion object {
        const val SCAN="SCAN"
        const val CONNECT="CONNECT"
        const val START_DR="START_DR"
        const val STOP_DR="STOP_DR"
        const val EXIT="EXIT"
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val bt by lazy { getSystemService(BluetoothManager::class.java).adapter }
    private val dr = DeadReckoner()
    private var gps: Location? = null
    private var gpsTime = 0L
    private var speedTime = 0L
    private var gyroTime = 0L
    private var tickTime = 0L
    private var gravity = floatArrayOf(0f,0f,9.81f)
    private var yawBias = 0.0
    private var mockOn = false
    private var connected = false
    private val responseBuffer = StringBuilder()
    private val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST96")
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
            if (connected && uartReady && tx != null) {
                // Never send another ELM command until the preceding prompt (>) arrives.
                // Initializing ISO9141 / KWP can take several seconds.
                if (pendingCommand != null && now - lastSentAt > 7000L) {
                    processElmReply("TIMEOUT")
                }
                if (pendingCommand == null && now - lastPoll >= 900L) {
                    val command = when {
                        setupIndex < commands.size -> commands[setupIndex]
                        configuredProtocol != protocolIndex -> "ATSP${protocols[protocolIndex].first}"
                        else -> "010D"
                    }
                    write(command)
                }
            }
            if(State.dr) {
                val dt = ((now - tickTime)/1000.0).coerceIn(0.0,0.5)
                if(State.speed!=null && now-speedTime < 3000) {
                    dr.move(State.speed!!,dt)
                    dr.pose?.let { p ->
                        val location = Location(LocationManager.GPS_PROVIDER).apply {
                            latitude=p.lat; longitude=p.lon; bearing=p.bearing.toFloat()
                            speed=State.speed!!/3.6f; accuracy=15f
                            time=System.currentTimeMillis(); elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                        }
                        try { lm.setTestProviderLocation(LocationManager.GPS_PROVIDER,location) }
                        catch(e:Exception) { State.message="Mock Location: ${e.message}" }
                        State.position="%.6f, %.6f; курс %.1f".format(Locale.US,p.lat,p.lon,p.bearing)
                    }
                } else State.message="Нет свежих данных OBD: расчёт остановлен"
            }
            tickTime=now
            handler.postDelayed(this,250)
        }
    }
    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val channel = NotificationChannel("nav","OBD GPS Navigator",NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val intent = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this,"nav").setContentTitle("OBD GPS Navigator").setContentText("GPS/OBD monitoring")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(intent).build()
        startForeground(1,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,this,Looper.getMainLooper()) } catch (_:Exception) {}
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME) }
        tickTime=SystemClock.elapsedRealtime()
        handler.post(loop)
    }
    override fun onStartCommand(i: Intent?,flags:Int,startId:Int): Int {
        when(i?.action) {
            SCAN -> scan()
            CONNECT -> i.getStringExtra("address")?.let { connect(it) }
            START_DR -> startDr()
            STOP_DR -> stopDr()
            EXIT -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }
    private fun scan() {
        try {
            val s=bt.bluetoothLeScanner ?: error("Bluetooth выключен")
            scanner=s
            val cb=object: ScanCallback() {
                override fun onScanResult(type:Int,result:ScanResult) {
                    val addr=result.device.address
                    val name=result.scanRecord?.deviceName ?: runCatching {result.device.name}.getOrNull() ?: "BLE device"
                    State.devices[addr]=name
                }
                override fun onScanFailed(code:Int){ State.message="Scan error $code" }
            }
            scanCallback=cb
            s.startScan(cb)
            State.message="Поиск BLE (12 сек.)"
            handler.postDelayed({stopScan()},12000)
        } catch(e:Exception) {State.message="BLE scan: ${e.message}"}
    }
    private fun stopScan() {
        try { scanCallback?.let {scanner?.stopScan(it)} } catch(_:Exception) {}
        scanCallback=null
    }
    private fun connect(address:String) {
        stopScan()
        try {
            gatt?.close()
            tx=null; connected=false; uartReady=false
            setupIndex=0; protocolIndex=0; configuredProtocol=-1
            failedSpeedRequests=0; pendingCommand=null; responseBuffer.clear()
            State.speed=null
            State.deviceName=State.devices[address] ?: address
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
        val g=gatt?:return
        val c=tx?:return
        try {
            val kind=if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE !=0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val result=g.writeCharacteristic(c,"$command\r".toByteArray(Charsets.US_ASCII),kind)
            lastPoll=SystemClock.elapsedRealtime()
            if (result==BluetoothStatusCodes.SUCCESS) {
                pendingCommand=command
                lastSentAt=lastPoll
                responseBuffer.clear()
                if(command.startsWith("ATSP"))
                    State.message="K-Line: проверяю ${protocols[protocolIndex].second}"
            } else State.message="Ошибка BLE write $result: $command"
        } catch(e:Exception) { State.message="Write: ${e.message}" }
    }
    private fun feed(bytes:ByteArray) {
        responseBuffer.append(bytes.toString(Charsets.US_ASCII).replace("\u0000",""))
        if(responseBuffer.length>4096) responseBuffer.clear()
        val index=responseBuffer.indexOf(">")
        if(index<0) return
        val reply=responseBuffer.substring(0,index)
        responseBuffer.delete(0,index+1)
        processElmReply(reply)
    }
    private fun processElmReply(reply:String) {
        val command=pendingCommand ?: return
        pendingCommand=null
        val upper=reply.uppercase()
        if(command.startsWith("ATSP")) {
            if(upper.contains("?") || upper.contains("ERROR") || upper.contains("TIMEOUT")) {
                nextProtocol("ELM не принял $command")
            } else configuredProtocol=protocolIndex
            return
        }
        if(command.startsWith("AT")) {
            // Continue setup after prompt; a specific command may be unsupported
            // by a clone, but protocol probing will still determine compatibility.
            setupIndex++
            return
        }
        if(command=="010D") {
            val kmh=Obd.parseSpeed(reply)
            if(kmh!=null) {
                State.speed=kmh
                speedTime=SystemClock.elapsedRealtime()
                failedSpeedRequests=0
                State.message="${protocols[protocolIndex].second}: $kmh км/ч (PID 010D)"
            } else {
                failedSpeedRequests++
                val detail=upper.replace("\r"," ").replace("\n"," ").trim().takeLast(54)
                State.message="${protocols[protocolIndex].second}: нет скорости (\$failedSpeedRequests/3): $detail"
                if(failedSpeedRequests>=3 && protocolIndex<protocols.lastIndex)
                    nextProtocol("PID 010D не ответил")
                // The last mode remains in auto; do not fake wheel speed.
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
        try {gatt?.close()} catch(_:Exception){}
        sm.unregisterListener(this)
        try {lm.removeUpdates(this)} catch(_:Exception){}
        super.onDestroy()
    }
}
