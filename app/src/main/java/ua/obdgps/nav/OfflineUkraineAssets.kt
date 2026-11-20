package ua.obdgps.nav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Read-only upgrade compatibility check. The Ukraine country downloader in
 * earlier APKs used filesDir/offline-vectors/selected.map + selected.poi.
 * Never rename, move, or delete those potentially 1GB user-owned files.
 */
object OfflineUkraineAssets {
    data class Check(
        val mapValid:Boolean,
        val poiValid:Boolean,
        val mapSize:Long,
        val poiSize:Long,
        val mapError:String,
        val poiError:String,
        val otherMaps:Int,
        val otherPois:Int,
        val partialMap:Boolean,
        val partialPoi:Boolean
    ) {
        val fullyAvailable get()=mapValid&&poiValid
    }

    private val worker=Executors.newSingleThreadExecutor{r->
        Thread(r,"Offline-Ukraine-Check").apply{isDaemon=true}
    }
    private val main=Handler(Looper.getMainLooper())

    /**
     * Fast header checks may run on UI thread. SQLite schema/row check
     * intentionally runs on worker to keep the offline screen responsive.
     */
    fun validMapHeader(file:File):Boolean{
        if(!file.isFile||file.length()<=1024L)return false
        return runCatching{
            file.inputStream().use{input->
                val header=ByteArray(20)
                input.read(header)==header.size &&
                    String(header,Charsets.US_ASCII)=="mapsforge binary OSM"
            }
        }.getOrDefault(false)
    }

    fun validPoiHeader(file:File):Boolean{
        if(!file.isFile||file.length()<=1024L)return false
        return runCatching{
            file.inputStream().use{input->
                val header=ByteArray(16)
                input.read(header)==header.size &&
                    String(header,Charsets.US_ASCII)=="SQLite format 3\u0000"
            }
        }.getOrDefault(false)
    }

    private fun validatePois(file:File):String{
        if(!validPoiHeader(file))return "Файл отсутствует или заголовок SQLite повреждён"
        return runCatching{
            val db=SQLiteDatabase.openDatabase(file.absolutePath,null,
                SQLiteDatabase.OPEN_READONLY)
            try{
                val tables=setOf("poi_data","poi_index")
                for(table in tables){
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(table)).use{cursor->
                            if(!cursor.moveToFirst())
                                error("Нет таблицы $table")
                        }
                    db.rawQuery("SELECT id FROM $table LIMIT 1",null).use{cursor->
                        if(!cursor.moveToFirst())error("Пустая таблица $table")
                    }
                }
            }finally{db.close()}
            ""
        }.getOrElse{it.localizedMessage?:"Ошибка проверки SQLite"}
    }

    fun scanAsync(context:Context,onDone:(Check)->Unit){
        val app=context.applicationContext
        worker.execute{
            val map=OfflineVectorStore.mapFile(app)
            val poi=OfflineVectorStore.poiFile(app)
            // These thresholds match the older *nationwide* Mapsforge
            // download service. A smaller imported Kyiv/oblast file may
            // still render as a fallback, but is not called "all Ukraine".
            val mapFormatOk=validMapHeader(map)
            val mapValid=mapFormatOk && map.length()>=40_000_000L
            val poiFormatError=validatePois(poi)
            val poiValid=poiFormatError.isEmpty() &&
                poi.length()>=8_000_000L
            val poiError=if(!poiValid && poiFormatError.isEmpty())
                "База меньше ожидаемого размера базы всей Украины"
            else poiFormatError
            val regionRoot=File(app.filesDir,"offline-region-packs")
            val otherMaps=regionRoot.listFiles()?.count{
                it.extension=="map"&&validMapHeader(it)
            }?:0
            val otherPois=regionRoot.listFiles()?.count{
                it.extension=="poi"&&validPoiHeader(it)
            }?:0
            val check=Check(
                mapValid=mapValid,
                poiValid=poiValid,
                mapSize=if(map.isFile)map.length() else 0L,
                poiSize=if(poi.isFile)poi.length() else 0L,
                mapError=when{
                    mapValid->""
                    !map.exists()->"Файл ещё не скачан"
                    mapFormatOk->"Найдена карта меньшего размера, чем ожидается для всей Украины"
                    else->"Не распознана карта Mapsforge"
                },
                poiError=if(!poi.exists())"Файл ещё не скачан" else poiError,
                otherMaps=otherMaps,
                otherPois=otherPois,
                partialMap=File(map.parentFile,"download-ukraine.map.part").exists(),
                partialPoi=File(poi.parentFile,"download-ukraine.poi.part").exists()
            )
            main.post{onDone(check)}
        }
    }
}
