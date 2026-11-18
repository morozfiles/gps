package ua.obdgps.nav

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Import a destination from a public Google Maps URL. No Google API key.
 * Only Google Maps hosts are followed, and never on the UI thread.
 *
 * A Places place_id alone cannot be resolved to coordinates without a
 * Places API; use the accompanying query/visible name instead.
 */
internal object GoogleMapsLinks {
    sealed class Target {
        data class Coordinate(
            val lat:Double,val lon:Double,
            val label:String="Google Maps",
            val approximate:Boolean=false
        ):Target()
        data class Address(val query:String):Target()
        data class ShortUrl(val url:String):Target()
    }

    private val embeddedUrl=Regex(
        """https?://[^\s<>\\"'«»]+""",RegexOption.IGNORE_CASE)
    private val coordinate=Regex(
        """^\s*(?:loc:|geo:)?\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""",
        RegexOption.IGNORE_CASE)
    private val atCoordinates=Regex(
        """/@(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)(?:,|/|$)""")
    private val exactCoordinates=Regex(
        """!3d(-?\d{1,2}(?:\.\d+)?)!4d(-?\d{1,3}(?:\.\d+)?)""")
    private val exactCoordinatesReverse=Regex(
        """!4d(-?\d{1,3}(?:\.\d+)?)!3d(-?\d{1,2}(?:\.\d+)?)""")
    private val viewCoordinates=Regex(
        """!2d(-?\d{1,3}(?:\.\d+)?)!3d(-?\d{1,2}(?:\.\d+)?)""")

    private fun supported(uri:Uri):Boolean {
        if(uri.scheme?.lowercase(Locale.US)!="https")return false
        return when(uri.host?.lowercase(Locale.US)){
            "google.com","www.google.com","maps.google.com",
            "google.com.ua","www.google.com.ua","maps.google.com.ua" ->
                (uri.host.orEmpty().startsWith("maps.") ||
                    uri.path.orEmpty().startsWith("/maps"))
            "maps.app.goo.gl" -> true
            "goo.gl" -> uri.path.orEmpty().startsWith("/maps/")
            else -> false
        }
    }

    /** Return the Google URL even when it is embedded in Share text. */
    fun extract(text:String):String? {
        val raw=text.trim()
        val candidates=(if(raw.any{it.isWhitespace()})emptyList()
            else listOf(raw))+embeddedUrl.findAll(raw).map{
            it.value.trimEnd('.',',',';',':','!','?',')',']','}','>','\u2026')
        }
        return candidates.firstOrNull{runCatching{
            supported(Uri.parse(it))
        }.getOrDefault(false)}
    }

    /**
     * Google Maps often shares "venue, street, house number, city, postcode".
     * Photon may return ZERO candidates for the whole phrase even when
     * "venue, city" resolves the intended POI. Preserve the city to avoid
     * matching an identically named place in another town.
     */
    fun addressVariants(raw:String):List<String>{
        val full=raw.replace(Regex("""\s+""")," ").trim()
        if(full.isBlank())return emptyList()
        val parts=full.split(',').map{it.trim()}.filter{it.isNotBlank()}
        val variants=ArrayList<String>()
        variants.add(full)
        if(parts.size>=3){
            val cityIndex=parts.indexOfLast{
                it.any(Char::isLetter) &&
                    !it.matches(Regex("""\d{4,8}"""))
            }
            val city=parts.getOrNull(cityIndex)?.takeIf{
                cityIndex>=2 && cityIndex<parts.size && it!=parts.first()
            }
            if(city!=null){
                variants.add(parts.first()+", "+city)
                if(cityIndex>=3) {
                    variants.add(parts.subList(1,cityIndex).joinToString(", ")+
                        ", "+city)
                }else if(cityIndex==2){
                    variants.add(parts[1]+", "+city)
                }
            }
        }
        if(parts.size==2){
            // A simpler place + city name may already be ideal.
            variants.add(parts.joinToString(", "))
        }
        return variants.map{it.trim().take(180)}
            .filter{it.length>=3}.distinct().take(4)
    }

    private fun point(lat:Double?,lon:Double?,label:String,
                      approximate:Boolean=false):Target.Coordinate? {
        if(lat==null||lon==null||!lat.isFinite()||!lon.isFinite()||
            lat !in -85.0..85.0||lon !in -180.0..180.0)return null
        return Target.Coordinate(lat,lon,label,approximate)
    }

    private fun parseLatLon(raw:String?,label:String):Target.Coordinate? {
        if(raw.isNullOrBlank())return null
        val match=coordinate.matchEntire(raw)?:return null
        return point(match.groupValues[1].toDoubleOrNull(),
            match.groupValues[2].toDoubleOrNull(),label)
    }

    fun parse(text:String):Target? {
        val url=extract(text)?:return null
        val uri=Uri.parse(url)
        val host=uri.host?.lowercase(Locale.US)
        if(host=="maps.app.goo.gl" || host=="goo.gl")
            return Target.ShortUrl(url)

        val path=Uri.decode(uri.encodedPath.orEmpty())
        val full=Uri.decode(url)
        val source=when {
            path.contains("/dir/") -> "Маршрут Google Maps"
            path.contains("/place/") -> "Место Google Maps"
            else -> "Точка Google Maps"
        }

        // Directions URL: choose the destination, never the origin.
        // query_place_id and destination_place_id without a name are not
        // location coordinates and cannot be looked up offline.
        for(key in listOf("destination","daddr","query","q")) {
            val value=uri.getQueryParameter(key)?.trim().orEmpty()
            if(value.isBlank())continue
            parseLatLon(value,source)?.let{return it}
            val readable=value.removePrefix("loc:").trim()
            if(readable.isNotBlank() && readable.length<=400)
                return Target.Address(readable)
        }

        // Google Maps place links embed the actual point in !3d...!4d...
        // or sometimes !4d...!3d... regardless of viewport position.
        exactCoordinates.find(full)?.let{
            return point(it.groupValues[1].toDoubleOrNull(),
                it.groupValues[2].toDoubleOrNull(),source)
        }
        exactCoordinatesReverse.find(full)?.let{
            return point(it.groupValues[2].toDoubleOrNull(),
                it.groupValues[1].toDoubleOrNull(),source)
        }

        // Older share links use !2d longitude + !3d latitude.
        viewCoordinates.find(full)?.let{
            return point(it.groupValues[2].toDoubleOrNull(),
                it.groupValues[1].toDoubleOrNull(),
                "Примерный центр Google Maps",approximate=true)
        }

        val segments=uri.pathSegments
        val dirIndex=segments.indexOf("dir")
        if(dirIndex>=0 && dirIndex+1<segments.size){
            val last=segments.lastOrNull()?.replace('+',' ')?.trim().orEmpty()
            parseLatLon(last,source)?.let{return it}
            if(last.isNotBlank() && last!="dir" && last.length<=400 &&
                !last.startsWith("@"))return Target.Address(last)
        }

        val placeIndex=segments.indexOf("place")
        if(placeIndex>=0 && placeIndex+1<segments.size) {
            val slug=segments[placeIndex+1].replace('+',' ').trim()
            if(slug.isNotBlank() && !slug.startsWith("@") && slug.length<=400){
                parseLatLon(slug,source)?.let{return it}
                // A textual place name is more reliable than the camera
                // viewport /@lat,lon, which may not be on the marker.
                return Target.Address(slug)
            }
        }

        uri.getQueryParameter("ll")?.let{parseLatLon(it,source)?.let{return it}}
        atCoordinates.find(full)?.let{
            return point(it.groupValues[1].toDoubleOrNull(),
                it.groupValues[2].toDoubleOrNull(),
                "Центр карты Google Maps",approximate=true)
        }
        return null
    }

    /**
     * Resolve standard HTTPS redirects from maps.app.goo.gl / goo.gl/maps.
     * All hosts are allowlisted, redirect count and URL size are bounded.
     */
    fun resolveShort(url:String):Target {
        var current=url
        val seen=HashSet<String>()
        repeat(7) {
            val uri=Uri.parse(current)
            if(!supported(uri))error("Переадресация за пределы Google Maps")
            if(!seen.add(current))error("Циклическая переадресация")
            val conn=URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects=false
            conn.requestMethod="GET"
            conn.connectTimeout=8500
            conn.readTimeout=8500
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Android) OBD-GPS-Navigator")
            conn.setRequestProperty("Accept-Encoding","identity")
            try{
                val status=conn.responseCode
                if(status in 300..399){
                    val location=conn.getHeaderField("Location")
                        ?:error("Google Maps не вернул адрес перехода")
                    val next=URL(URL(current),location).toString()
                    if(next.length>8192)error("Слишком длинный адрес")
                    current=next
                    val parsed=parse(current)
                    if(parsed!=null && parsed !is Target.ShortUrl)return parsed
                    return@repeat
                }
                if(status!=200)error("Google Maps вернул HTTP $status")
                // Some short links return a page rather than a redirect.
                // Never evaluate scripts or load external content.
                val snippet=conn.inputStream.bufferedReader().use{reader->
                    val buffer=CharArray(8192)
                    val output=StringBuilder()
                    while(output.length<96_000){
                        val count=reader.read(buffer,0,
                            minOf(buffer.size,96_000-output.length))
                        if(count<=0)break
                        output.append(buffer,0,count)
                    }
                    output.toString()
                }
                val normalized=snippet.replace("&amp;","&")
                    .replace("\\/","/")
                for(match in embeddedUrl.findAll(normalized)){
                    val candidate=match.value.trimEnd('.',',',';',':','!',')',']')
                    if(candidate==current)continue
                    val parsed=parse(candidate)
                    if(parsed!=null && parsed !is Target.ShortUrl)return parsed
                }
                error("Не удалось извлечь координаты из короткой ссылки")
            }finally{conn.disconnect()}
        }
        error("Слишком много перенаправлений Google Maps")
    }
}
