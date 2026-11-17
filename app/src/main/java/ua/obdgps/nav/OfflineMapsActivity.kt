package ua.obdgps.nav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Offline region map management. No bulk requests to OSM's public tile API. */
class OfflineMapsActivity:Activity() {
    private val bg=Color.rgb(27,31,38)
    private val foreground=Color.rgb(238,242,248)
    private val muted=Color.rgb(170,191,210)
    private val pickMbtiles=4519
    private lateinit var status:TextView
    private lateinit var detail:TextView

    private fun dp(x:Int)=(resources.displayMetrics.density*x+.5f).toInt()

    private fun button(label:String,onClick:()->Unit):Button =
        Button(this).apply {
            text=label
            textSize=14f
            isAllCaps=false
            minHeight=0
            minimumHeight=0
            setTextColor(foreground)
            background=GradientDrawable().apply{
                cornerRadius=dp(10).toFloat()
                setColor(Color.rgb(47,54,65))
                setStroke(dp(1),Color.rgb(82,96,112))
            }
            setPadding(dp(9),dp(9),dp(9),dp(9))
            setOnClickListener {onClick()}
        }

    private fun info():String {
        val imported=OfflineMapStore.hasImportedRegion(this)
        val size=OfflineMapStore.importedSize(this)
        val visited=OfflineMapStore.viewedSize(this)
        return "Карта области: "+if(imported)
            "сохранена ("+formatSize(size)+")" else "не импортирована"
    }

    private fun formatSize(bytes:Long):String =
        String.format(Locale.getDefault(),"%.1f МБ",bytes/1048576.0)

    private fun updateStatus() {
        if(!::status.isInitialized)return
        status.text=info()
        detail.text="Просмотренные карты: "+
            formatSize(OfflineMapStore.viewedSize(this))+
            "\nМесто хранения: память приложения, сохраняется при перезапуске"
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18),dp(14),dp(18),dp(14))
            setOnApplyWindowInsetsListener{view,insets->
                val bars=insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(dp(18)+bars.left,dp(14)+bars.top,
                    dp(18)+bars.right,dp(14)+bars.bottom)
                insets
            }
        }
        val scroll=ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text="Офлайн-карты"
            textSize=24f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(foreground)
            setPadding(0,0,0,dp(15))
        })
        status=TextView(this).apply{
            setTextColor(foreground);textSize=16f
            setPadding(0,dp(5),0,dp(4))
        }
        root.addView(status)
        detail=TextView(this).apply{
            setTextColor(muted);textSize=13f
            setPadding(0,0,0,dp(15))
        }
        root.addView(detail)
        root.addView(button("Импортировать карту области (.mbtiles)") {
            val request=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                addCategory(Intent.CATEGORY_OPENABLE)
                type="*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/x-sqlite3",
                    "application/vnd.sqlite3",
                    "application/octet-stream",
                    "application/x-mbtiles"
                ))
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(request,pickMbtiles)
            } catch (e:Exception) {
                Toast.makeText(this,"Не удалось открыть файлы: "+
                    (e.localizedMessage?:"нет проводника"),Toast.LENGTH_LONG).show()
            }
        },LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,dp(58)))
        root.addView(TextView(this).apply{
            text="Готовая растровая карта области хранится на телефоне. "+
                "Поддерживается формат MBTiles (PNG/JPEG/WebP), "+
                "полученный из источника, разрешающего скачивание для офлайна. "+
                "Векторные .pbf и .osm не подходят."
            textSize=13f
            setTextColor(muted)
            setPadding(0,dp(10),0,dp(18))
        })
        root.addView(button("Удалить импортированную карту") {
            AlertDialog.Builder(this).setTitle("Удалить офлайн-область?")
                .setMessage("Удалится импортированный файл MBTiles. "+
                    "Сохранённые просмотренные тайлы останутся.")
                .setPositiveButton("Удалить"){_,_->
                    if(OfflineMapStore.deleteRegion(this))
                        Toast.makeText(this,"Офлайн-карта удалена",Toast.LENGTH_SHORT).show()
                    updateStatus()
                }.setNegativeButton("Отмена",null).show()
        },LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,dp(52)))
        root.addView(button("Очистить просмотренные участки") {
            AlertDialog.Builder(this).setTitle("Очистить сохранённые тайлы?")
                .setMessage("Карта, которую приложение открывало через интернет, "+
                    "больше не будет доступна без сети, если она не покрыта MBTiles.")
                .setPositiveButton("Очистить"){_,_->
                    val number=OfflineMapStore.clearViewedTiles(this)
                    Toast.makeText(this,"Удалено: "+number,Toast.LENGTH_SHORT).show()
                    updateStatus()
                }.setNegativeButton("Отмена",null).show()
        },LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,dp(52)).apply{
                topMargin=dp(9)
            })
        root.addView(TextView(this).apply {
            text="Участки карты, которые ты открываешь, сохраняются автоматически " +
                "и не загружаются повторно при каждом старте. " +
                "Загрузка целых областей с публичного сервера OpenStreetMap " +
                "запрещена его правилами; для полноценной офлайн-области " +
                "используй MBTiles от провайдера с разрешённой офлайн-загрузкой. " +
                "Поиск адреса и перестроение маршрута пока требуют интернета."
            textSize=13f
            setTextColor(muted)
            setPadding(0,dp(19),0,dp(14))
        })
        root.addView(button("Готово"){finish()},
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(46)))
        updateStatus()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=pickMbtiles || resultCode!=RESULT_OK)return
        val uri:Uri=data?.data?:return
        status.text="Проверяю и сохраняю файл карты..."
        OfflineMapStore.importRegion(this,uri){result->
            if(isFinishing || isDestroyed)return@importRegion
            result.fold(
                onSuccess={message->
                    Toast.makeText(this,message,Toast.LENGTH_LONG).show()
                    updateStatus()
                },
                onFailure={error->
                    status.text="Не удалось сохранить карту: "+
                        (error.localizedMessage?:"некорректный файл")
                    detail.text="Выбери растровый MBTiles-файл с тайлами."
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
