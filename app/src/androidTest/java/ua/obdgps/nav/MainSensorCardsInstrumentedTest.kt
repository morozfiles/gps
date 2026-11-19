package ua.obdgps.nav

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verify actual Android first-screen layout and the sensor settings action. */
@RunWith(AndroidJUnit4::class)
class MainSensorCardsInstrumentedTest {
    private fun buttons(view:View):List<Button>{
        val found=ArrayList<Button>()
        if(view is Button)found.add(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)
            found.addAll(buttons(view.getChildAt(i)))
        return found
    }

    @Test fun sensorsShowInOneHorizontalRowWithLiveTextAndHelp(){
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sensorButtons=buttons(activity.window.decorView).filter {
                    it.text.toString().startsWith("Компас") ||
                    it.text.toString().startsWith("Гироскоп") ||
                    it.text.toString().startsWith("Акселерометр")
                }
                assertEquals(3,sensorButtons.size)
                val titles=listOf("Компас","Гироскоп","Акселерометр")
                for((index,button) in sensorButtons.withIndex()){
                    assertTrue(button.text.toString().startsWith(titles[index]))
                    assertTrue(button.parent is LinearLayout)
                    assertSame(sensorButtons[0].parent,button.parent)
                    val params=button.layoutParams as LinearLayout.LayoutParams
                    assertEquals(0,params.width)
                    assertTrue(params.weight>0)
                    assertTrue(button.text.toString().contains("\n"))
                }
                val field=activity.javaClass.getDeclaredField("sensorHint")
                    .apply {isAccessible=true}
                val hint=field.get(activity) as TextView
                assertTrue(hint.text.isNotBlank())
                // Each button opens its own sensor calibration dialog.
                sensorButtons[0].performClick()
                val selected=activity.javaClass.getDeclaredField("selectedSensor")
                    .apply {isAccessible=true}.get(activity)
                assertEquals(CalibrationSensor.COMPASS,selected)
                assertTrue(hint.text.toString().contains("Компас"))
            }
        }
    }
}
