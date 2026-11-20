package ua.obdgps.nav

import android.content.Context
import java.io.File

/**
 * Independent Mapsforge packs: a small nationwide overview and detailed
 * oblasts. Never delete another oblast when a new one is selected.
 *
 * The pack server is deliberately configurable: there is no public,
 * verified Mapsforge-per-oblast CDN that we can safely hardcode. A pack
 * publisher can serve <base>/<id>.map and <base>/<id>.poi over HTTPS.
 */
object OfflineRegionPacks {
    data class Region(val id:String,val name:String,val lat:Double,val lon:Double)
    val regions=listOf(
        Region("kyiv","Киевская область + Киев",50.4501,30.5234),
        Region("vinnytsia","Винницкая область",49.2331,28.4682),
        Region("volyn","Волынская область",50.7472,25.3254),
        Region("dnipropetrovsk","Днепропетровская область",48.4647,35.0462),
        Region("donetsk","Донецкая область",48.0159,37.8029),
        Region("zhytomyr","Житомирская область",50.2547,28.6587),
        Region("zakarpattia","Закарпатская область",48.6208,22.2879),
        Region("zaporizhzhia","Запорожская область",47.8388,35.1396),
        Region("ivano-frankivsk","Ивано-Франковская область",48.9226,24.7111),
        Region("kirovohrad","Кировоградская область",48.5079,32.2623),
        Region("luhansk","Луганская область",48.5740,39.3078),
        Region("lviv","Львовская область",49.8397,24.0297),
        Region("mykolaiv","Николаевская область",46.9750,31.9946),
        Region("odesa","Одесская область",46.4825,30.7233),
        Region("poltava","Полтавская область",49.5883,34.5514),
        Region("rivne","Ровненская область",50.6199,26.2516),
        Region("sumy","Сумская область",50.9077,34.7981),
        Region("ternopil","Тернопольская область",49.5535,25.5948),
        Region("kharkiv","Харьковская область",49.9935,36.2304),
        Region("kherson","Херсонская область",46.6354,32.6169),
        Region("khmelnytskyi","Хмельницкая область",49.4229,26.9871),
        Region("cherkasy","Черкасская область",49.4444,32.0598),
        Region("chernivtsi","Черновицкая область",48.2921,25.9358),
        Region("chernihiv","Черниговская область",51.4982,31.2893),
        Region("crimea","АР Крым",44.9521,34.1024),
        Region("sevastopol","Севастополь",44.6167,33.5254)
    )
    const val OVERVIEW="overview"
    const val DEFAULT="kyiv"
    private const val PREFS="offline-region-packs"

    private fun prefs(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)

    fun selected(context:Context):String =
        prefs(context).getString("selected",DEFAULT)?.takeIf{value->
            regions.any{it.id==value}
        }?:DEFAULT

    fun select(context:Context,id:String){
        require(regions.any{it.id==id}){"Неизвестная область"}
        prefs(context).edit().putString("selected",id).apply()
    }

    fun region(id:String):Region?=regions.firstOrNull{it.id==id}

    /** User-owned/static CDN directory. No public OSM tile scraping. */
    fun baseUrl(context:Context):String=prefs(context)
        .getString("base_url","").orEmpty().trim().trimEnd('/')

    fun setBaseUrl(context:Context,url:String) {
        val value=url.trim().trimEnd('/')
        require(value.isEmpty() ||
            (value.startsWith("https://") && android.net.Uri.parse(value).host!=null &&
                !value.contains("?") && !value.contains("#"))
        ){"Нужен адрес HTTPS без параметров"}
        prefs(context).edit().putString("base_url",value).apply()
    }

    fun wifiOnly(context:Context)=prefs(context).getBoolean("wifi_only",true)
    fun autoDownload(context:Context)=prefs(context).getBoolean("auto_download",true)
    fun setWifiOnly(context:Context,value:Boolean)=
        prefs(context).edit().putBoolean("wifi_only",value).apply()
    fun setAutoDownload(context:Context,value:Boolean)=
        prefs(context).edit().putBoolean("auto_download",value).apply()

    fun url(context:Context,id:String,extension:String):String? {
        if(id!=OVERVIEW && region(id)==null)return null
        if(extension !in setOf("map","poi"))return null
        val base=baseUrl(context)
        if(base.isBlank())return null
        return "$base/$id.$extension"
    }

    private fun root(context:Context):File=
        File(context.filesDir,"offline-region-packs").apply{mkdirs()}

    fun file(context:Context,id:String,extension:String):File {
        require(id==OVERVIEW || region(id)!=null)
        require(extension=="map"||extension=="poi")
        return File(root(context),"$id.$extension")
    }

    fun has(context:Context,id:String,extension:String="map")=
        file(context,id,extension).let{it.isFile && it.length()>1024}

    fun delete(context:Context,id:String):Boolean {
        if(id!=OVERVIEW && region(id)==null)return false
        val m=file(context,id,"map").delete()
        val p=file(context,id,"poi").delete()
        return m||p
    }

    /**
     * Upgrade compatibility: nationwide files saved in older APKs have
     * priority. Existing region files remain readable as optional fallback,
     * but downloading/importing oblast packs is no longer offered in the UI.
     */
    fun mapFiles(context:Context):List<File> {
        val output=ArrayList<File>()
        val country=OfflineVectorStore.mapFile(context)
        if(OfflineUkraineAssets.validMapHeader(country) &&
            country.length()>=40_000_000L){
            // All-Ukraine vector data is self-contained: do not open
            // duplicated old oblast maps on top of the nationwide map.
            return listOf(country)
        }
        if(OfflineUkraineAssets.validMapHeader(country))output.add(country)
        for(id in listOf(selected(context),OVERVIEW)) {
            val file=file(context,id,"map")
            if(OfflineUkraineAssets.validMapHeader(file) &&
                output.none{it.absolutePath==file.absolutePath})
                output.add(file)
        }
        return output
    }

    /** Country-wide POI from the old version first, then saved legacy packs. */
    fun poiFiles(context:Context):List<File> {
        val output=ArrayList<File>()
        val country=OfflineVectorStore.poiFile(context)
        if(OfflineUkraineAssets.validPoiHeader(country) &&
            country.length()>=8_000_000L){
            // Prefer the national address database instead of searching
            // duplicated, potentially obsolete oblast-specific indexes.
            return listOf(country)
        }
        if(OfflineUkraineAssets.validPoiHeader(country))output.add(country)
        for(id in listOf(selected(context),OVERVIEW)) {
            val file=file(context,id,"poi")
            if(OfflineUkraineAssets.validPoiHeader(file) &&
                output.none{it.absolutePath==file.absolutePath})
                output.add(file)
        }
        return output
    }

    fun needsDefaultPacks(context:Context):Boolean =
        autoDownload(context) && baseUrl(context).isNotBlank() &&
            (!has(context,OVERVIEW) || !has(context,DEFAULT))
}
