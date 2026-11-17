package ua.obdgps.nav

import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Informational ONLY. Public, unofficial Tryvoha.online feed. Never treat
 * a green dot as an official all-clear signal.
 *
 * One HTTPS request to /api/v1/alerts while the Activity is visible.
 * Refresh no more than once per minute; show UNKNOWN if the feed is stale,
 * malformed or unavailable.
 */
object AirAlerts {
    enum class Level { UNKNOWN, CLEAR, OBLAST, CITY }

    data class Snapshot(
        val level: Level,
        val explanation: String,
        val sourceUpdatedUtcMs: Long = 0L
    )

    private const val ENDPOINT = "https://tryvoha.online/api/v1/alerts"
    private const val POLL_INTERVAL_MS = 60_000L
    private const val MAX_SOURCE_AGE_MS = 180_000L
    private const val FUTURE_TOLERANCE_MS = 90_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AirAlert-Fetch").apply { isDaemon = true }
    }
    private val latest = AtomicReference(Snapshot(Level.UNKNOWN, "Тревоги: загрузка..."))
    private var lastPollElapsed = -POLL_INTERVAL_MS
    private var fetching = false

    fun snapshot(): Snapshot {
        val s = latest.get()
        if (s.level != Level.UNKNOWN) {
            val age = System.currentTimeMillis() - s.sourceUpdatedUtcMs
            if (age > MAX_SOURCE_AGE_MS || age < -FUTURE_TOLERANCE_MS)
                return Snapshot(Level.UNKNOWN, "Тревоги: нет свежих данных")
        }
        return s
    }

    fun refreshIfDue() {
        synchronized(this) {
            val elapsed = SystemClock.elapsedRealtime()
            if (fetching || elapsed - lastPollElapsed < POLL_INTERVAL_MS) return
            fetching = true
            lastPollElapsed = elapsed
        }
        executor.execute {
            try {
                latest.set(readAlertStatus())
            } catch (e: Exception) {
                latest.set(Snapshot(Level.UNKNOWN, "Тревоги: данные недоступны"))
            } finally {
                synchronized(this) { fetching = false }
            }
        }
    }

    private fun readAlertStatus(): Snapshot {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "OBD-GPS-Navigator/0.4.0 (Android)")
        connection.useCaches = false

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK)
                throw IllegalStateException("HTTP ${connection.responseCode}")
            val jsonText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (jsonText.length > 512_000) throw IllegalStateException("Response too large")

            val data = JSONObject(jsonText)
            val sourceTime = Instant.parse(data.getString("updated_at")).toEpochMilli()
            val age = System.currentTimeMillis() - sourceTime
            if (age > MAX_SOURCE_AGE_MS || age < -FUTURE_TOLERANCE_MS)
                throw IllegalStateException("Stale alert data")

            val alerts = data.getJSONArray("alerts")
            var cityActive = false
            var oblastActive = false
            for (i in 0 until alerts.length()) {
                val entry = alerts.getJSONObject(i)
                val slug = entry.optString("slug")
                val oblast = entry.optString("oblast_slug")
                // Kyiv city is a separate alert region; it is NOT Kyiv Oblast.
                if (slug == "kyiv") cityActive = true
                if (slug == "kyivska" || oblast == "kyivska") {
                    // A district-only alert also counts as an oblast alert.
                    oblastActive = true
                }
            }

            return when {
                cityActive -> Snapshot(Level.CITY, "Тревога в Киеве", sourceTime)
                oblastActive -> Snapshot(Level.OBLAST, "Тревога в Киевской области", sourceTime)
                else -> Snapshot(Level.CLEAR, "Киев и область: нет активных тревог в данных", sourceTime)
            }
        } finally {
            connection.disconnect()
        }
    }
}
