package ua.obdgps.nav

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/** Private on-device search preferences; never sent to GitHub or Google Cloud. */
internal class SearchPlacesPrefs(context:Context) {
    private val prefs=context.getSharedPreferences(
        "phone_google_places_v1",Context.MODE_PRIVATE)

    var homeAddress:String
        get()=prefs.getString("home_address","").orEmpty()
        set(value){
            prefs.edit().putString("home_address",value.trim().take(300)).apply()
        }

    fun recent():List<String> = runCatching {
        val arr=JSONArray(prefs.getString("recent_queries","[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }
        }.take(5)
    }.getOrDefault(emptyList())

    fun remember(query:String) {
        val updated=RecentSearches.add(recent(),query)
        val arr=JSONArray()
        updated.forEach { arr.put(it) }
        prefs.edit().putString("recent_queries",arr.toString()).apply()
    }
}

/** Deterministic ordering and deduplication independent of Android UI. */
internal object RecentSearches {
    fun add(existing:List<String>,query:String):List<String> {
        val clean=query.trim().replace(Regex("\\s+")," ").take(300)
        if(clean.isBlank())return existing.take(5)
        return (listOf(clean)+existing).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(5)
    }
}
