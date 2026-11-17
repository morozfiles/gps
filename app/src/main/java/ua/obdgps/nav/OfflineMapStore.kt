package ua.obdgps.nav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Offline map infrastructure, separate from the live OpenStreetMap tile service.
 *
 * Tiles fetched while ON SCREEN may be cached for ≥7 days for repeat visits.
 * A whole city/oblast MUST NOT be prefetched from tile.openstreetmap.org
 * (OSMF tile policy). For true offline coverage users import a raster MBTiles
 * region archive obtained from a provider permitting offline redistribution.
 */
object OfflineMapStore {
    private val worker=Executors.newSingleThreadExecutor { r ->
        Thread(r,"Offline-MBTiles-Import").apply {isDaemon=true}
    }
    private val main=Handler(Looper.getMainLooper())
    @Volatile var generation=0
        private set

    private fun folder(context:Context)=File(context.filesDir,"offline-maps")
        .apply{mkdirs()}

    fun visibleTileFile(context:Context,key:String):File=
        File(File(folder(context),"viewed").apply{mkdirs()},key+".png")

    private fun mbtiles(context:Context)=File(folder(context),"selected-region.mbtiles")

    fun hasImportedRegion(context:Context):Boolean=mbtiles(context).let{
        it.isFile && it.length()>1024L
    }

    fun importedSize(context:Context):Long=mbtiles(context).length()
    fun viewedSize(context:Context):Long =
        File(folder(context),"viewed").listFiles()
            ?.filter{it.isFile}?.sumOf{it.length()}?:0L

    /**
     * TMS row order in MBTiles is inverted relative to XYZ tiles on screen.
     * Read-only DB is opened on a background tile worker, never on the UI
     * thread. No vector (pbf) tiles: the native canvas requires raster.
     */
    fun readRasterTile(context:Context,z:Int,x:Int,y:Int):ByteArray? {
        val file=mbtiles(context)
        if(!file.isFile || z !in 0..22)return null
        val db=try {
            SQLiteDatabase.openDatabase(file.absolutePath,null,
                SQLiteDatabase.OPEN_READONLY)
        } catch (_:Exception){return null}
        try {
            val tmsY=(1L shl z)-1L-y
            if(tmsY<0L)return null
            val tile=db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? "+
                    "AND tile_column=? AND tile_row=? LIMIT 1",
                arrayOf(z.toString(),x.toString(),tmsY.toString())
            )
            tile.use {
                if(!it.moveToFirst())return null
                val bytes=it.getBlob(0)?:return null
                if(bytes.size>2_000_000 || bytes.isEmpty())return null
                // Reject compressed vector data; only raster is supported.
                val header=bytes.take(12)
                val png=header.size>=8 && header[0]==0x89.toByte() &&
                    header[1]==0x50.toByte() && header[2]==0x4e.toByte()
                val jpg=header.size>=3 && header[0]==0xff.toByte() &&
                    header[1]==0xd8.toByte()
                val webp=header.size>=12 &&
                    String(bytes,0,4,Charsets.US_ASCII)=="RIFF" &&
                    String(bytes,8,4,Charsets.US_ASCII)=="WEBP"
                if(!png && !jpg && !webp)return null
                return bytes
            }
        } catch (_:Exception) {
            return null
        } finally {db.close()}
    }

    /**
     * Imports a map chosen with Android's file picker into app-private files.
     * Does not download bulk tiles. A failed import leaves the old map intact.
     */
    fun importRegion(context:Context,uri:Uri,callback:(Result<String>)->Unit) {
        val app=context.applicationContext
        worker.execute {
            val result=runCatching {
                val temp=File(folder(app),"selected-region.part")
                val target=mbtiles(app)
                temp.delete()
                try {
                    val stream=app.contentResolver.openInputStream(uri)
                        ?:throw IllegalStateException("Не удаётся открыть файл карты")
                    var size=0L
                    stream.use { input ->
                        temp.outputStream().use{out->
                            val buffer=ByteArray(64*1024)
                            while(true) {
                                val n=input.read(buffer)
                                if(n<0)break
                                size+=n
                                if(size>1_500_000_000L)
                                    throw IllegalStateException("Карта больше 1,5 ГБ")
                                out.write(buffer,0,n)
                            }
                        }
                    }
                    if(size<1024L)throw IllegalStateException("Файл карты пуст")
                    val db=SQLiteDatabase.openDatabase(temp.absolutePath,null,
                        SQLiteDatabase.OPEN_READONLY)
                    try {
                        val cursor=db.rawQuery(
                            "SELECT name FROM sqlite_master "+
                            "WHERE name='tiles' AND type IN ('table','view')",null)
                        cursor.use {
                            if(!it.moveToFirst())
                                throw IllegalStateException("В файле нет таблицы tiles (MBTiles)")
                        }
                        val sample=db.rawQuery("SELECT tile_data FROM tiles LIMIT 1",null)
                        sample.use {
                            if(!it.moveToFirst() || it.getBlob(0)==null)
                                throw IllegalStateException("Карта не содержит растровые тайлы")
                            val bytes=it.getBlob(0)!!
                            if(BitmapFactory.decodeByteArray(bytes,0,bytes.size)==null)
                                throw IllegalStateException(
                                    "Нужна растровая карта MBTiles (PNG/JPEG/WebP), не векторная")
                        }
                    } finally {db.close()}
                    val backup=File(folder(app),"selected-region.old")
                    backup.delete()
                    if(target.exists() && !target.renameTo(backup))
                        throw IllegalStateException("Не удалось заменить предыдущую карту")
                    if(!temp.renameTo(target)) {
                        backup.renameTo(target)
                        throw IllegalStateException("Не удалось сохранить карту на телефон")
                    }
                    backup.delete()
                    generation++
                    "Карта сохранена для офлайна: "+
                        (size/1024/1024)+" МБ"
                } finally {temp.delete()}
            }
            main.post{callback(result)}
        }
    }

    fun deleteRegion(context:Context):Boolean {
        val removed=mbtiles(context).delete()
        if(removed)generation++
        return removed
    }

    fun clearViewedTiles(context:Context):Int {
        var deleted=0
        File(folder(context),"viewed").listFiles()?.forEach {
            if(it.isFile && it.delete())deleted++
        }
        generation++
        return deleted
    }
}
