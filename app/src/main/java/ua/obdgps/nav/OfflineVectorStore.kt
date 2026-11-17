package ua.obdgps.nav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Local Mapsforge .map drawing + independent .poi search store.
 * No network request is made from this class.
 */
object OfflineVectorStore {
    private val worker=Executors.newSingleThreadExecutor { r ->
        Thread(r,"Offline-Vector-Import").apply{isDaemon=true}
    }
    private val main=Handler(Looper.getMainLooper())

    private fun root(context:Context):File=
        File(context.filesDir,"offline-vectors").apply{mkdirs()}
    fun mapFile(context:Context):File=File(root(context),"selected.map")
    fun poiFile(context:Context):File=File(root(context),"selected.poi")
    fun hasMap(context:Context)=mapFile(context).let{it.isFile&&it.length()>100}
    fun hasPois(context:Context)=poiFile(context).let{it.isFile&&it.length()>100}
    private fun validType(kind:String)=kind=="map"||kind=="poi"

    fun delete(context:Context,kind:String):Boolean{
        if(!validType(kind))return false
        return (if(kind=="map")mapFile(context) else poiFile(context)).delete()
    }

    fun importFile(context:Context,uri:Uri,kind:String,
                   onProgress:(String)->Unit,
                   onDone:(Result<String>)->Unit) {
        if(!validType(kind)) {
            onDone(Result.failure(IllegalArgumentException("Неизвестный формат")))
            return
        }
        val app=context.applicationContext
        worker.execute {
            val result=runCatching {
                val file=if(kind=="map")mapFile(app) else poiFile(app)
                val tmp=File(root(app),"selected.$kind.part")
                tmp.delete()
                try {
                    var total=0L
                    val max=if(kind=="map")1_500_000_000L else 600_000_000L
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use{ out ->
                            val buf=ByteArray(128*1024)
                            var lastReport=0L
                            while(true) {
                                val read=input.read(buf)
                                if(read<0)break
                                total+=read
                                if(total>max)throw IllegalArgumentException(
                                    "Файл превышает ограничение размера")
                                out.write(buf,0,read)
                                if(total-lastReport>8*1024*1024) {
                                    lastReport=total
                                    main.post {onProgress("Сохранено "+(total/1024/1024)+" МБ")}
                                }
                            }
                        }
                    }?:throw IllegalStateException("Не удалось открыть файл")
                    if(total<1024L)throw IllegalStateException("Файл пуст")
                    if(kind=="map") {
                        tmp.inputStream().use{input->
                            val magic=ByteArray(20)
                            if(input.read(magic)!=magic.size ||
                                String(magic,Charsets.US_ASCII)!=
                                "mapsforge binary OSM")
                                throw IllegalStateException(
                                    "Это не векторная карта Mapsforge .map")
                        }
                    } else {
                        val db=SQLiteDatabase.openDatabase(tmp.absolutePath,null,
                            SQLiteDatabase.OPEN_READONLY)
                        try {
                            db.rawQuery("SELECT id,data FROM poi_data LIMIT 1",null).use{
                                if(!it.moveToFirst())throw IllegalStateException(
                                    "В файле нет данных Mapsforge POI")
                            }
                            db.rawQuery("SELECT id FROM poi_index LIMIT 1",null).use{
                                if(!it.moveToFirst())throw IllegalStateException(
                                    "В файле нет индекса точек")
                            }
                        } finally {db.close()}
                    }
                    val bak=File(root(app),"selected.$kind.bak")
                    bak.delete()
                    if(file.exists()&&!file.renameTo(bak))
                        throw IllegalStateException("Не удаётся заменить карту")
                    if(!tmp.renameTo(file)) {
                        if(bak.exists())bak.renameTo(file)
                        throw IllegalStateException("Не удалось сохранить файл")
                    }
                    bak.delete()
                    "Сохранено "+(total/1024/1024)+" МБ. "+
                        if(kind=="map")"Векторная карта готова" else
                            "Офлайн-поиск доступен"
                } finally{tmp.delete()}
            }
            main.post {onDone(result)}
        }
    }

    data class Place(val label:String,val lat:Double,val lon:Double)

    /**
     * Mapsforge POI has v3 index (lat, lon), or v4 R-Tree index
     * (minLat,maxLat,minLon,maxLon). Data tags contain key=value separated
     * by carriage returns. Older POI archives may not contain every building.
     */
    fun findPlaces(context:Context,query:String,limit:Int=8):List<Place> {
        val file=poiFile(context)
        val term=query.trim().lowercase(Locale.getDefault())
        if(!file.isFile||term.length<3)return emptyList()
        val terms=term.split(Regex("[\\s,]+")).filter{
            it.length>=2 && it !in setOf("місто","город","вулиця","улица",
                "область","район","будинок","дом","проспект","проспект",
                "україна","украина")
        }
        // City names often aren't stored on every individual OSM POI.
        // Treat explicit Kyiv prefix as a geographic ranking hint rather
        // than requiring the string to exist in every building record.
        val kyivCityQuery=terms.any{it=="київ"||it=="киев"}
        val tokens=if(terms.size>1)terms.filterNot {
            it in setOf("київ","киев","м.","г.")
        } else terms
        if(tokens.isEmpty())return emptyList()
        val numeric=tokens.firstOrNull{it.any(Char::isDigit)}
        val leading=tokens.filter{it!=numeric}.maxByOrNull{it.length}
            ?:tokens.maxByOrNull{it.length}?:return emptyList()
        val patterns=linkedSetOf<String>().apply {
            add(leading)
            add(leading.replaceFirstChar{it.titlecase(Locale.getDefault())})
            add(leading.uppercase(Locale.getDefault()))
        }
        val db=try {
            SQLiteDatabase.openDatabase(file.absolutePath,null,
                SQLiteDatabase.OPEN_READONLY)
        } catch (_:Exception){return emptyList()}
        try {
            val columnSet=mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(poi_index)",null).use{cursor->
                val i=cursor.getColumnIndex("name")
                while(cursor.moveToNext())columnSet.add(cursor.getString(i))
            }
            val coordinates=if("lat" in columnSet)
                "idx.lat,idx.lon" else
                "(idx.minLat+idx.maxLat)/2.0,(idx.minLon+idx.maxLon)/2.0"
            val sql="SELECT p.data,$coordinates FROM poi_data p "+
                "JOIN poi_index idx ON p.id=idx.id WHERE "+
                patterns.joinToString(" OR ",prefix="(",postfix=")"){"p.data LIKE ?"}+
                " LIMIT 700"
            val out=ArrayList<Place>()
            db.rawQuery(sql,patterns.map{"%$it%"}.toTypedArray()).use {cursor->
                while(cursor.moveToNext()) {
                    val data=cursor.getString(0)?:continue
                    val lower=data.lowercase(Locale.getDefault())
                    if(tokens.any{it !in lower})continue
                    val attrs=data.split('\r','\n')
                        .mapNotNull {
                            val at=it.indexOf('=')
                            if(at<=0)null else it.substring(0,at) to it.substring(at+1)
                        }.toMap()
                    val name=attrs["name:uk"]?:attrs["name:ru"]?:attrs["name"]
                    val street=attrs["addr:street"]
                    val number=attrs["addr:housenumber"]
                    val city=attrs["addr:city"]
                    if(numeric!=null &&
                        (number==null ||
                         !number.lowercase(Locale.getDefault()).startsWith(numeric)))continue
                    val label=listOfNotNull(name,street,number,city)
                        .distinct().filter{it.isNotBlank()}.joinToString(", ")
                    if(label.isBlank())continue
                    val lat=cursor.getDouble(1)
                    val lon=cursor.getDouble(2)
                    if(!lat.isFinite()||!lon.isFinite()||
                        lat !in -85.0..85.0||lon !in -180.0..180.0)continue
                    out.add(Place(PlaceSearch.cyrillicLabel(label),lat,lon))
                    if(out.size>=minOf(500,limit*50))break
                }
            }
            val distinct=out.distinctBy{it.lat to it.lon}
            return if(kyivCityQuery)distinct.sortedBy {
                val dy=(it.lat-50.4501)*111_195.0
                val dx=(it.lon-30.5234)*70_000.0
                dy*dy+dx*dx
            }.take(limit) else distinct.take(limit)
        } catch (_:Exception){return emptyList()}
        finally{db.close()}
    }
}
