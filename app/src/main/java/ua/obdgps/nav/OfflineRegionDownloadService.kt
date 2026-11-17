package ua.obdgps.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.StatFs
import android.os.SystemClock
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Automatic first-run overview + Kyiv packs and manual per-oblast downloads.
 * The pack URLs must point to prepared Mapsforge files on an HTTPS server.
 * This never downloads public OpenStreetMap raster tiles.
 */
class OfflineRegionDownloadService:Service(){
    companion object {
        const val AUTO="ua.obdgps.nav.packs.AUTO"
        const val DOWNLOAD_OVERVIEW="ua.obdgps.nav.packs.OVERVIEW"
        const val DOWNLOAD_REGION="ua.obdgps.nav.packs.REGION"
        const val STOP="ua.obdgps.nav.packs.STOP"
        const val REGION_ID="region_id"
        private const val CHANNEL="offline-region-packs"
        private const val NOTICE=8438
        private const val MAX_MAP=1_800_000_000L
        private const val MAX_POI=600_000_000L
        @Volatile private var active=false
        @Volatile private var stopRequested=false
        data class Progress(
            val running:Boolean=false,
            val title:String="Загрузки пока нет",
            val downloaded:Long=0,
            val total:Long=0,
            val message:String=""
        )
        @Volatile var progress=Progress()
            private set
    }

    private val executor=Executors.newSingleThreadExecutor {r->
        Thread(r,"Oblast-Offline-Downloader").apply{isDaemon=true}
    }
    private var lastNotify=0L
    override fun onBind(intent:Intent?):IBinder?=null

    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Офлайн-карты областей",
                NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val action=intent?.action
        if(action==STOP){
            stopRequested=true
            return START_NOT_STICKY
        }
        if(action!=AUTO && action!=DOWNLOAD_OVERVIEW &&
            action!=DOWNLOAD_REGION) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        synchronized(OfflineRegionDownloadService::class.java){
            if(active)return START_NOT_STICKY
            active=true
            stopRequested=false
        }
        val regionId=intent?.getStringExtra(REGION_ID)?:OfflineRegionPacks.DEFAULT
        progress=Progress(true,"Подготовка загрузки",message="Проверяю хранилище")
        startForeground(NOTICE,notice(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        executor.execute {
            try {
                if(OfflineRegionPacks.baseUrl(this).isBlank())
                    error("Сначала укажи HTTPS-адрес сервера пакетов")
                if(OfflineRegionPacks.wifiOnly(this) && !isUnmetered())
                    error("Подключись к Wi-Fi или выключи ограничение в настройках карт")
                val work=when(action){
                    AUTO -> buildList {
                        if(!OfflineRegionPacks.has(this@OfflineRegionDownloadService,
                                OfflineRegionPacks.OVERVIEW))
                            add(OfflineRegionPacks.OVERVIEW to "map")
                        for(kind in listOf("map","poi"))
                            if(!OfflineRegionPacks.has(this@OfflineRegionDownloadService,
                                    OfflineRegionPacks.DEFAULT,kind))
                                add(OfflineRegionPacks.DEFAULT to kind)
                    }
                    DOWNLOAD_OVERVIEW -> listOf(OfflineRegionPacks.OVERVIEW to "map")
                    else -> {
                        require(OfflineRegionPacks.region(regionId)!=null){
                            "Неизвестная область"}
                        listOf(regionId to "map",regionId to "poi")
                    }
                }
                work.forEach{(id,kind)->download(id,kind)}
                progress=Progress(title="Офлайн-карты готовы",
                    message="Файлы сохранены на телефоне и доступны без сети")
            } catch(e:InterruptedException) {
                progress=Progress(title="Загрузка остановлена",
                    message="Частичные файлы сохранены для продолжения")
            } catch(e:Exception) {
                progress=Progress(title="Не удалось скачать карту",
                    message=e.localizedMessage?:"Ошибка сети")
            } finally {
                synchronized(OfflineRegionDownloadService::class.java){
                    active=false
                    stopRequested=false
                }
                postNotice(true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun isUnmetered():Boolean{
        val manager=getSystemService(ConnectivityManager::class.java)
        val current=manager.activeNetwork?:return false
        val c=manager.getNetworkCapabilities(current)?:return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun checkStop() {
        if(stopRequested||Thread.currentThread().isInterrupted)
            throw InterruptedException("Пользователь остановил загрузку")
    }

    private fun validFile(kind:String,file:File) {
        if(file.length()<1024L || file.length()>
            if(kind=="map")MAX_MAP else MAX_POI)
            error("Неверный размер загруженного файла")
        if(kind=="map"){
            FileInputStream(file).use { stream->
                val magic=ByteArray(20)
                if(stream.read(magic)!=20 ||
                    String(magic,Charsets.US_ASCII)!="mapsforge binary OSM")
                    error("Полученный файл не является Mapsforge .map")
            }
        }else{
            val db=SQLiteDatabase.openDatabase(file.absolutePath,null,
                SQLiteDatabase.OPEN_READONLY)
            try{
                db.rawQuery("SELECT id FROM poi_index LIMIT 1",null).use{
                    if(!it.moveToFirst())error("Нет индекса POI")
                }
                db.rawQuery("SELECT id,data FROM poi_data LIMIT 1",null).use{
                    if(!it.moveToFirst())error("Нет данных адресов")
                }
                db.rawQuery("PRAGMA quick_check(1)",null).use{
                    if(!it.moveToFirst()||it.getString(0)!="ok")
                        error("Повреждённый файл POI")
                }
            }finally{db.close()}
        }
    }

    private fun download(id:String,kind:String){
        checkStop()
        val target=OfflineRegionPacks.file(this,id,kind)
        val urlText=OfflineRegionPacks.url(this,id,kind)
            ?:error("Нет URL для карты $id")
        val title=if(id==OfflineRegionPacks.OVERVIEW)
            "Обзорная карта Украины"
        else (OfflineRegionPacks.region(id)?.name?:id)+
            (if(kind=="map")" · дороги" else " · адреса")
        val part=File(target.parentFile,"$id.$kind.part")
        val maxBytes=if(kind=="map")MAX_MAP else MAX_POI
        if(part.length()>maxBytes)part.delete()
        val offset=part.length()
        val conn=URL(urlText).openConnection() as HttpURLConnection
        conn.connectTimeout=20_000
        conn.readTimeout=30_000
        conn.instanceFollowRedirects=true
        conn.setRequestProperty("Accept-Encoding","identity")
        conn.setRequestProperty("User-Agent","OBD-GPS-Navigator/0.10 offline-packs")
        if(offset>0)conn.setRequestProperty("Range","bytes=$offset-")
        try{
            val status=conn.responseCode
            if(status!=200 && status!=206)
                error("Сервер карт вернул HTTP $status ($id.$kind)")
            if(conn.url.protocol!="https")
                error("Перенаправление на незащищённый HTTP")
            val resume=offset>0L && status==206 &&
                conn.getHeaderField("Content-Range").orEmpty()
                    .startsWith("bytes $offset-")
            val start=if(resume)offset else 0L
            val total=conn.contentLengthLong.takeIf{it>0L}?.plus(start)?:0L
            if(total>maxBytes)error("Файл превышает ограничение размера")
            val available=StatFs(target.parentFile!!.absolutePath).availableBytes
            if(total>0L && available < (total-start+16*1024*1024))
                error("Недостаточно свободного места на телефоне")
            progress=Progress(true,title,start,total,
                "Можно свернуть приложение; загрузка продолжится")
            postNotice(true)
            var saved=start
            RandomAccessFile(part,"rw").use{out->
                out.setLength(start)
                out.seek(start)
                conn.inputStream.use {input->
                    val buffer=ByteArray(128*1024)
                    var last=0L
                    while(true){
                        checkStop()
                        val count=input.read(buffer)
                        if(count<0)break
                        saved+=count
                        if(saved>maxBytes)error("Файл слишком большой")
                        out.write(buffer,0,count)
                        val t=SystemClock.elapsedRealtime()
                        if(t-last>1000L){
                            last=t
                            progress=progress.copy(downloaded=saved)
                            postNotice()
                        }
                    }
                }
            }
            if(total>0L && saved!=total)error("Загрузка прервана")
            validFile(kind,part)
            checkStop()
            val backup=File(target.parentFile,"$id.$kind.old")
            backup.delete()
            if(target.exists()&&!target.renameTo(backup))
                error("Не удалось обновить старую карту")
            if(!part.renameTo(target)){
                if(backup.exists())backup.renameTo(target)
                error("Не удалось сохранить загруженный файл")
            }
            backup.delete()
            progress=progress.copy(downloaded=saved,total=saved,
                message="Сохранено в память приложения")
            postNotice(true)
        } finally{conn.disconnect()}
    }

    private fun notice():Notification {
        val p=progress
        val back=PendingIntent.getActivity(this,0,
            Intent(this,OfflineMapsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,0,
            Intent(this,OfflineRegionDownloadService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent=if(p.total>0)
            (p.downloaded*100L/p.total).coerceIn(0L,100L).toInt() else 0
        return Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(p.title)
            .setContentText(if(p.running)
                "${p.downloaded/1048576} МБ / "+
                    (if(p.total>0)"${p.total/1048576} МБ" else "? МБ")
                else p.message)
            .setContentIntent(back)
            .setOngoing(p.running)
            .setOnlyAlertOnce(true)
            .setProgress(100,percent,p.running && p.total==0L)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",stop)
            .build()
    }

    private fun postNotice(force:Boolean=false){
        val now=SystemClock.elapsedRealtime()
        if(!force&&now-lastNotify<950L)return
        lastNotify=now
        getSystemService(NotificationManager::class.java)
            .notify(NOTICE,notice())
    }

    override fun onDestroy(){
        executor.shutdownNow()
        super.onDestroy()
    }
}
