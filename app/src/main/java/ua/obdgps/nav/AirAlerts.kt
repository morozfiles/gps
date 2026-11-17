package ua.obdgps.nav

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Informational ONLY. Unofficial Tryvoha.online data may be delayed.
 * Never treat this indicator as an official all-clear signal.
 *
 * Kyiv city ("kyiv") is a separate territory from Kyiv Oblast ("kyivska").
 * Other selectable regions are oblasts; a district-only alert is shown as
 * an alert somewhere within that oblast.
 */
object AirAlerts {
    enum class Level { UNKNOWN, CLEAR, OBLAST, CITY }

    data class Region(
        val slug:String,
        val name:String,
        val shortName:String,
        val city:Boolean=false
    )

    val regions:List<Region> = listOf(
        Region("kyiv","Киев (город)","Киев",true),
        Region("vinnytska","Винницкая область","Винницкая обл."),
        Region("volynska","Волынская область","Волынская обл."),
        Region("dnipropetrovska","Днепропетровская область","Днепропетровская обл."),
        Region("donetska","Донецкая область","Донецкая обл."),
        Region("zhytomyrska","Житомирская область","Житомирская обл."),
        Region("zakarpatska","Закарпатская область","Закарпатская обл."),
        Region("zaporizka","Запорожская область","Запорожская обл."),
        Region("ivano-frankivska","Ивано-Франковская область","Ивано-Франковская обл."),
        Region("kyivska","Киевская область","Киевская обл."),
        Region("kirovohradska","Кировоградская область","Кировоградская обл."),
        Region("luhanska","Луганская область","Луганская обл."),
        Region("lvivska","Львовская область","Львовская обл."),
        Region("mykolaivska","Николаевская область","Николаевская обл."),
        Region("odeska","Одесская область","Одесская обл."),
        Region("poltavska","Полтавская область","Полтавская обл."),
        Region("rivnenska","Ровненская область","Ровненская обл."),
        Region("sumska","Сумская область","Сумская обл."),
        Region("ternopilska","Тернопольская область","Тернопольская обл."),
        Region("kharkivska","Харьковская область","Харьковская обл."),
        Region("khersonska","Херсонская область","Херсонская обл."),
        Region("khmelnytska","Хмельницкая область","Хмельницкая обл."),
        Region("cherkaska","Черкасская область","Черкасская обл."),
        Region("chernivetska","Черновицкая область","Черновицкая обл."),
        Region("chernihivska","Черниговская область","Черниговская обл.")
    )

    val oblasts:List<Region> = regions.filterNot {it.city}
    val cities:List<Region> = regions.filter {it.city}

    data class Snapshot(
        val level:Level,
        val explanation:String,
        val sourceUpdatedUtcMs:Long=0L,
        val region:Region=cities.first()
    )

    private data class ActiveAlert(val slug:String,val oblastSlug:String)
    private data class Feed(val updatedAt:Long,val alerts:List<ActiveAlert>)

    private const val ENDPOINT="https://tryvoha.online/api/v1/alerts"
    private const val POLL_INTERVAL_MS=60_000L
    private const val MAX_SOURCE_AGE_MS=180_000L
    private const val FUTURE_TOLERANCE_MS=90_000L

    private val executor=Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable,"AirAlert-Fetch").apply {isDaemon=true}
    }
    private val feed=AtomicReference<Feed?>(null)
    @Volatile private var selectedCitySlug="kyiv"
    @Volatile private var selectedOblastSlug="kyivska"
    private var lastPollElapsed=-POLL_INTERVAL_MS
    private var fetching=false

    fun initialize(context:Context) {
        val prefs=NavigatorPrefs(context)
        selectedCitySlug=prefs.alertCitySlug
        selectedOblastSlug=prefs.alertOblastSlug
        refreshIfDue()
    }

    fun currentCity():Region=
        cities.firstOrNull{it.slug==selectedCitySlug}?:cities.first()

    fun currentOblast():Region=
        oblasts.firstOrNull{it.slug==selectedOblastSlug}
            ?:oblasts.first{it.slug=="kyivska"}

    fun selectCity(context:Context,slug:String) {
        val region=cities.firstOrNull{it.slug==slug}?:return
        NavigatorPrefs(context).alertCitySlug=region.slug
        selectedCitySlug=region.slug
        refreshIfDue()
    }

    fun selectOblast(context:Context,slug:String) {
        val region=oblasts.firstOrNull{it.slug==slug}?:return
        NavigatorPrefs(context).alertOblastSlug=region.slug
        selectedOblastSlug=region.slug
        refreshIfDue()
    }

    fun citySnapshot():Snapshot=snapshotFor(currentCity())
    fun oblastSnapshot():Snapshot=snapshotFor(currentOblast())

    /** Used by auto DR: only a fresh city-specific alarm can arm it. */
    fun selectedCityHasAlert():Boolean=citySnapshot().level==Level.CITY

    private fun snapshotFor(region:Region):Snapshot {
        val raw=feed.get()?:return Snapshot(
            Level.UNKNOWN,"Тревоги · "+region.name+": свежие данные недоступны",
            region=region)
        val age=System.currentTimeMillis()-raw.updatedAt
        if(age>MAX_SOURCE_AGE_MS || age< -FUTURE_TOLERANCE_MS)
            return Snapshot(Level.UNKNOWN,
                "Тревоги · "+region.name+": нет свежих данных",region=region)

        val active=if(region.city)raw.alerts.any{it.slug==region.slug}
            else raw.alerts.any{
                it.slug==region.slug || it.oblastSlug==region.slug
            }
        val level=when {
            !active -> Level.CLEAR
            region.city -> Level.CITY
            else -> Level.OBLAST
        }
        val message=if(active)"Воздушная тревога: "+region.name else
            "По данным Tryvoha.online: "+region.name+" — тревога не указана"
        return Snapshot(level,message,raw.updatedAt,region)
    }

    fun refreshIfDue() {
        synchronized(this) {
            val elapsed=SystemClock.elapsedRealtime()
            if(fetching || elapsed-lastPollElapsed<POLL_INTERVAL_MS)return
            fetching=true
            lastPollElapsed=elapsed
        }
        executor.execute {
            try {
                feed.set(readFeed())
            } catch (_:Exception) {
                // Never show stale green/red indicators when the feed fails.
                feed.set(null)
            } finally {
                synchronized(this){fetching=false}
            }
        }
    }

    private fun readFeed():Feed {
        val conn=URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.connectTimeout=8_000
        conn.readTimeout=8_000
        conn.requestMethod="GET"
        conn.setRequestProperty("Accept","application/json")
        conn.setRequestProperty("User-Agent","OBD-GPS-Navigator/0.9.7 (Android)")
        conn.useCaches=false
        try {
            if(conn.responseCode!=HttpURLConnection.HTTP_OK)
                throw IllegalStateException("Alerts HTTP "+conn.responseCode)
            val json=conn.inputStream.bufferedReader(Charsets.UTF_8).use{
                val text=it.readText()
                if(text.length>512_000)throw IllegalStateException("Oversized alert feed")
                JSONObject(text)
            }
            val sourceTime=Instant.parse(json.getString("updated_at")).toEpochMilli()
            val age=System.currentTimeMillis()-sourceTime
            if(age>MAX_SOURCE_AGE_MS || age< -FUTURE_TOLERANCE_MS)
                throw IllegalStateException("Stale alert data")
            val list=json.getJSONArray("alerts")
            val alerts=ArrayList<ActiveAlert>(list.length())
            for(i in 0 until list.length()) {
                val a=list.optJSONObject(i)?:continue
                val slug=a.optString("slug","")
                val parent=a.optString("oblast_slug","")
                if(slug.isNotBlank())alerts.add(ActiveAlert(slug,parent))
            }
            return Feed(sourceTime,alerts)
        } finally { conn.disconnect() }
    }
}
