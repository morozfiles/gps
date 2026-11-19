package ua.obdgps.nav

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.text.TextUtils
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Compact two-dot indicator used on BOTH main and navigator screens.
 * Left: oblast (Kyiv Oblast by default). Right: city (Kyiv by default).
 * Red = active, green = no active alert, gray = no fresh feed.
 */
class AirAlertIndicatorsView(context:Context):LinearLayout(context) {
    private val colorText=Color.rgb(191,205,218)
    private val clock=DateTimeFormatter.ofPattern("HH:mm")
        .withZone(KyivTimeZone.resolve())
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private data class Indicator(val dot:View,val name:TextView)
    private val oblast:Indicator
    private val city:Indicator

    init {
        orientation=HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
        val pair=makeIndicator("Область")
        oblast=pair.first
        addView(pair.second,LayoutParams(dp(84),dp(53)))
        val other=makeIndicator("Город")
        city=other.first
        addView(other.second,LayoutParams(dp(84),dp(53)))
        setOnClickListener {
            val o=AirAlerts.oblastSnapshot()
            val c=AirAlerts.citySnapshot()
            val timestamp=maxOf(o.sourceUpdatedUtcMs,c.sourceUpdatedUtcMs)
            val updated=if(timestamp>0L)
                "\nДанные: "+clock.format(Instant.ofEpochMilli(timestamp)) else ""
            Toast.makeText(context,
                "Область: "+o.explanation+"\nГород: "+c.explanation+updated+
                "\nTryvoha.online · неофициальные данные",
                Toast.LENGTH_LONG).show()
        }
        isClickable=true
        isFocusable=true
        refresh()
    }

    private fun makeIndicator(caption:String):Pair<Indicator,LinearLayout> {
        val box=LinearLayout(context).apply {
            orientation=VERTICAL
            gravity=Gravity.CENTER
        }
        val title=TextView(context).apply {
            text=caption
            textSize=10f
            setTextColor(colorText)
            gravity=Gravity.CENTER
            includeFontPadding=false
        }
        box.addView(title)
        val dot=View(context)
        box.addView(dot,LayoutParams(dp(20),dp(20)).apply {
            gravity=Gravity.CENTER
            topMargin=dp(3)
            bottomMargin=dp(3)
        })
        val name=TextView(context).apply {
            textSize=10f
            setTextColor(Color.rgb(232,240,249))
            gravity=Gravity.CENTER
            isSingleLine=true
            ellipsize=TextUtils.TruncateAt.END
            includeFontPadding=false
        }
        box.addView(name,LayoutParams(LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT))
        return Indicator(dot,name) to box
    }

    private fun display(item:Indicator,alert:AirAlerts.Snapshot) {
        val color=when(alert.level) {
            AirAlerts.Level.CITY,AirAlerts.Level.OBLAST -> Color.rgb(218,48,38)
            AirAlerts.Level.CLEAR -> Color.rgb(27,166,82)
            AirAlerts.Level.UNKNOWN -> Color.rgb(143,153,164)
        }
        item.dot.background=GradientDrawable().apply {
            shape=GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1),Color.rgb(28,34,43))
        }
        item.name.text=if(alert.region.city)
            alert.region.shortName else alert.region.shortName.replace(" обл.","")
        val status=when(alert.level) {
            AirAlerts.Level.UNKNOWN -> "нет данных"
            AirAlerts.Level.CLEAR -> "нет тревоги"
            else -> "тревога"
        }
        item.dot.contentDescription=alert.region.name+": "+status
        item.name.contentDescription=item.dot.contentDescription
    }

    fun refresh() {
        display(oblast,AirAlerts.oblastSnapshot())
        display(city,AirAlerts.citySnapshot())
    }
}
