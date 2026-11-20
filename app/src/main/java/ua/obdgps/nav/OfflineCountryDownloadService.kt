package ua.obdgps.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * User-initiated country vector map download; all transfers use published
 * Mapsforge binaries, not the OpenStreetMap raster tiles service.
 *
 * Downloads to .part with Range resume. Only a successfully validated file
 * replaces the existing offline map, so a failure never destroys Kyiv.
 */
class OfflineCountryDownloadService:Service() {
    companion object {
        const val START_ALL="ua.obdgps.nav.download.UKRAINE_ALL"
        const val START_MAP="ua.obdgps.nav.download.UKRAINE_MAP"
        const val START_POI="ua.obdgps.nav.download.UKRAINE_POI"
        const val CANCEL="ua.obdgps.nav.download.CANCEL"
        private const val CHANNEL="offline-country-maps"
        private const val NOTIFICATION_ID=8412

        data class Progress(
            val running:Boolean=false,
            val title:String="Загрузка не запущена",
            val downloaded:Long=0,
            val total:Long=0,
            val overallLabel:String="",
            val message:String=""
        )
        @Volatile var progress=Progress()
            private set

        @Volatile private var cancelRequested=false
        @Volatile private var active=false
        private const val MAP_URL=
            "https://download.mapsforge.org/maps/v5/europe/ukraine.map"
        private const val POI_URL=
            "https://download.mapsforge.org/pois/europe/ukraine.poi"
        private const val MAP_MIRROR=
            "https://rhlx01.hs-esslingen.de/Mirrors/download.mapsforge.org/maps/v5/europe/ukraine.map"
        private const val POI_MIRROR=
            "https://rhlx01.hs-esslingen.de/Mirrors/download.mapsforge.org/pois/europe/ukraine.poi"
        private const val MIN_MAP=40_000_000L
        private const val MIN_POI=8_000_000L
        private const val MAX_MAP=1_800_000_000L
        private const val MAX_POI=600_000_000L
    }

    private val worker=Executors.newSingleThreadExecutor { task->
        Thread(task,"Offline-Ukraine-Downloader").apply{isDaemon=true}
    }
    private val handler=Handler(Looper.getMainLooper())
    private var lastNotificationMs=0L
    private var wakeCount=0

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,"Загрузка офлайн-карт",NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            CANCEL -> {
                cancelRequested=true
                progress=progress.copy(title="Остановка загрузки...")
                updateNotification(force=true)
                return START_NOT_STICKY
            }
            START_ALL, START_MAP, START_POI -> {
                synchronized(OfflineCountryDownloadService::class.java) {
                    if(active)return START_NOT_STICKY
                    active=true
                    cancelRequested=false
                }
                progress=Progress(running=true,title="Подготовка загрузки Украины",
                    message="Сохранение в память приложения")
                startForeground(NOTIFICATION_ID,buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                val requested=intent.action!!
                worker.execute {
                    try {
                        val jobs=when(requested){
                            START_MAP -> listOf("map")
                            START_POI -> listOf("poi")
                            else -> listOf("map","poi")
                        }
                        jobs.forEachIndexed {i,kind->
                            checkCancel()
                            transfer(kind,"Файл "+(i+1)+"/"+jobs.size)
                        }
                        progress=Progress(title="Украина сохранена офлайн",
                            message=if(requested==START_ALL)
                                "Дороги и адреса доступны без интернета"
                            else "Файл успешно сохранён")
                    } catch(e:Exception) {
                        val stopped=e is InterruptedException
                        progress=Progress(
                            title=if(stopped)"Загрузка остановлена"
                            else "Ошибка загрузки",
                            message=if(stopped)"Скачанные части сохранены; можно продолжить"
                            else (e.localizedMessage?:"Не удалось получить файл"))
                    } finally {
                        active=false
                        cancelRequested=false
                        updateNotification(force=true)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            else -> {stopSelf();return START_NOT_STICKY}
        }
    }

    private fun outputFile(kind:String):File =
        if(kind=="map")OfflineVectorStore.mapFile(this)
        else OfflineVectorStore.poiFile(this)

    private fun source(kind:String):Array<String> =
        if(kind=="map")arrayOf(MAP_URL,MAP_MIRROR)
        else arrayOf(POI_URL,POI_MIRROR)

    private fun validate(kind:String,file:File) {
        if(kind=="map") {
            if(file.length()<MIN_MAP||file.length()>MAX_MAP)
                throw IllegalArgumentException("Неверный размер карты Украины")
            FileInputStream(file).use { input ->
                val head=ByteArray(20)
                if(input.read(head)!=head.size||
                    String(head,Charsets.US_ASCII)!="mapsforge binary OSM")
                    throw IllegalStateException("Неверный формат карты .map")
            }
        } else {
            if(file.length()<MIN_POI||file.length()>MAX_POI)
                throw IllegalArgumentException("Неверный размер базы адресов")
            val db=SQLiteDatabase.openDatabase(file.absolutePath,null,
                SQLiteDatabase.OPEN_READONLY)
            try{
                db.rawQuery("SELECT id,data FROM poi_data LIMIT 1",null).use{
                    if(!it.moveToFirst())throw IllegalStateException("Нет индекса адресов")
                }
                db.rawQuery("SELECT id FROM poi_index LIMIT 1",null).use{
                    if(!it.moveToFirst())throw IllegalStateException("Нет координат POI")
                }
                val check=db.rawQuery("PRAGMA quick_check(1)",null)
                check.use{
                    if(!it.moveToFirst()||it.getString(0)!="ok")
                        throw IllegalStateException("База адресов повреждена")
                }
            }finally{db.close()}
        }
    }

    private fun transfer(kind:String,stage:String) {
        val dest=outputFile(kind)
        val partial=File(dest.parentFile,"download-ukraine.$kind.part")
        // An upgrade from an older APK must reuse the saved country file.
        // Validating on the background download worker is safe; never
        // replace a valid ~1GB map or SQLite database without need.
        val alreadyValid=dest.isFile && runCatching{
            validate(kind,dest)
            true
        }.getOrDefault(false)
        if(alreadyValid){
            progress=progress.copy(
                title=if(kind=="map")
                    "Карта Украины уже сохранена" else
                    "База адресов Украины уже сохранена",
                downloaded=dest.length(),total=dest.length(),
                overallLabel=stage,
                message="Файл из прошлой версии проверен и будет использован без загрузки"
            )
            updateNotification(force=true)
            return
        }
        val minimum=if(kind=="map")MIN_MAP else MIN_POI
        val maximum=if(kind=="map")MAX_MAP else MAX_POI
        if(dest.exists() && dest.length()>minimum) {
            // Download is explicitly requested: allow re-downloading to update
            // existing map; an interrupted transfer is resumed from .part.
        }
        val stat=StatFs(dest.parentFile!!.absolutePath)
        val bytesFree=stat.availableBytes
        val initial=partial.length()
        if(bytesFree < max(130L*1024L*1024L,
            (if(kind=="map")900_000_000L else 200_000_000L)-initial)) {
            throw IllegalStateException(
                "Недостаточно свободной памяти для Украины. "+
                "Для карты с адресами желательно 1,2 ГБ свободного места.")
        }
        var lastError:Exception?=null
        for(url in source(kind)) {
            checkCancel()
            try {
                download(url,partial,kind,stage,maximum)
                validate(kind,partial)
                checkCancel()
                val backup=File(dest.parentFile,"selected.$kind.previous")
                if(backup.exists())backup.delete()
                if(dest.exists()&&!dest.renameTo(backup))
                    throw IllegalStateException("Не удаётся обновить файл карты")
                if(!partial.renameTo(dest)) {
                    if(backup.exists())backup.renameTo(dest)
                    throw IllegalStateException("Не удалось сохранить карту")
                }
                backup.delete()
                progress=progress.copy(
                    downloaded=dest.length(),total=dest.length(),
                    title="Готово: "+if(kind=="map")"карта Украины" else "адреса Украины",
                    message="Файл сохранён в память телефона",
                    overallLabel=stage
                )
                updateNotification(force=true)
                return
            }catch(e:InterruptedException){throw e}
            catch(e:Exception){
                lastError=e
                progress=progress.copy(
                    message="Повторное подключение к зеркалу...")
                updateNotification(force=true)
            }
        }
        throw IllegalStateException(
            "Не удалось скачать "+(if(kind=="map")"карту" else "адреса")+
            ": "+(lastError?.localizedMessage?:"ошибка сети"))
    }

    private fun download(urlText:String,part:File,kind:String,
                         stage:String,maxBytes:Long){
        var offset=part.length()
        if(offset>maxBytes){part.delete();offset=0L}
        val conn=URL(urlText).openConnection() as HttpURLConnection
        conn.connectTimeout=20_000
        conn.readTimeout=28_000
        conn.requestMethod="GET"
        conn.instanceFollowRedirects=true
        conn.setRequestProperty("User-Agent",
            "OBD-GPS-Navigator/1.0 Android offline-map-downloader")
        conn.setRequestProperty("Accept-Encoding","identity")
        if(offset>0L)conn.setRequestProperty("Range","bytes=$offset-")
        try{
            val code=conn.responseCode
            if(code!=200&&code!=206)
                throw IllegalStateException("Сервер карт вернул HTTP $code")
            if(conn.url.protocol!="https")
                throw IllegalStateException("Сервер перенаправил на незащищённое соединение")
            val resume=offset>0L && code==206 &&
                conn.getHeaderField("Content-Range").orEmpty()
                    .startsWith("bytes $offset-")
            if(!resume)offset=0L
            val remaining=conn.contentLengthLong
            val total=if(remaining>0L)offset+remaining else 0L
            if(total>maxBytes)
                throw IllegalStateException("Размер файла превышает предел")
            var stored=offset
            var updated=0L
            progress=Progress(running=true,
                title=if(kind=="map")"Скачивание дорог Украины"
                      else "Скачивание адресов Украины",
                downloaded=stored,total=total,overallLabel=stage,
                message="Можно свернуть приложение — загрузка продолжится")
            updateNotification(force=true)
            RandomAccessFile(part,"rw").use {file->
                file.setLength(offset)
                file.seek(offset)
                conn.inputStream.use{input->
                    val buffer=ByteArray(128*1024)
                    while(true) {
                        checkCancel()
                        val n=input.read(buffer)
                        if(n<0)break
                        stored+=n
                        if(stored>maxBytes)
                            throw IllegalStateException("Слишком большой файл")
                        file.write(buffer,0,n)
                        val now=SystemClock.elapsedRealtime()
                        if(now-updated>900L) {
                            updated=now
                            progress=progress.copy(downloaded=stored)
                            updateNotification()
                        }
                    }
                }
            }
            progress=progress.copy(downloaded=stored,total=total)
            if(stored<if(kind=="map")MIN_MAP else MIN_POI)
                throw IllegalStateException("Загружен неполный файл")
            if(total>0 && stored!=total)
                throw IllegalStateException("Загрузка оборвалась")
        }finally{conn.disconnect()}
    }

    private fun checkCancel(){
        if(cancelRequested || Thread.currentThread().isInterrupted)
            throw InterruptedException("Загрузка отменена")
    }

    private fun buildNotification():Notification {
        val p=progress
        val back=PendingIntent.getActivity(this,0,
            Intent(this,OfflineMapsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel=PendingIntent.getService(this,1,
            Intent(this,OfflineCountryDownloadService::class.java)
                .setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent=if(p.total>0L)
            (p.downloaded*100L/p.total).coerceIn(0,100).toInt() else 0
        return Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(p.title)
            .setContentText(if(p.running)
                (p.downloaded/1048576L).toString()+" МБ / "+
                (if(p.total>0)(p.total/1048576L).toString()+" МБ"
                else "размер уточняется") else p.message)
            .setContentIntent(back)
            .setOngoing(p.running)
            .setOnlyAlertOnce(true)
            .setProgress(100,percent,p.total<=0L)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",cancel)
            .build()
    }

    private fun updateNotification(force:Boolean=false){
        val now=SystemClock.elapsedRealtime()
        if(!force&&now-lastNotificationMs<1100L)return
        lastNotificationMs=now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID,buildNotification())
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
