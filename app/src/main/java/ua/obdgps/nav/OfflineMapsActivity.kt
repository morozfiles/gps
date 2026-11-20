package ua.obdgps.nav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Nationwide offline Mapsforge map + POI. Upgrades must reuse files
 * previously downloaded into filesDir/offline-vectors/selected.*.
 * No oblast import, third-party HTTPS pack server, or automatic re-download.
 */
class OfflineMapsActivity:Activity(){
    private val bg=Color.rgb(27,31,38)
    private val primary=Color.rgb(238,242,248)
    private val secondary=Color.rgb(170,191,210)
    private val accent=Color.rgb(137,190,250)
    private val pickCountryMap=4520
    private val pickCountryPois=4521
    private val ui=Handler(Looper.getMainLooper())
    private lateinit var checkStatus:TextView
    private lateinit var otherStatus:TextView
    private lateinit var downloadStatus:TextView
    private lateinit var progressBar:ProgressBar
    private var lastSignature=""
    private var checking=false
    private var lastCheck:OfflineUkraineAssets.Check?=null
    private var pendingCountryDownload=false
    private val tick=object:Runnable{
        override fun run(){
            if(::downloadStatus.isInitialized)renderDownload()
            val signature=fileSignature()
            if(signature!=lastSignature && !checking)checkExistingFiles()
            ui.postDelayed(this,1400L)
        }
    }

    private fun dp(n:Int)=(n*resources.displayMetrics.density+0.5f).toInt()
    private fun size(bytes:Long):String=
        String.format(Locale.getDefault(),"%.1f МБ",bytes/1048576.0)

    private fun button(text:String,onClick:()->Unit)=Button(this).apply{
        this.text=text
        isAllCaps=false
        textSize=14f
        minHeight=0
        minimumHeight=0
        setTextColor(primary)
        background=GradientDrawable().apply{
            cornerRadius=dp(10).toFloat()
            setColor(Color.rgb(47,54,65))
            setStroke(dp(1),Color.rgb(82,96,112))
        }
        setPadding(dp(9),dp(9),dp(9),dp(9))
        setOnClickListener{onClick()}
    }

    private fun label(text:String,size:Float=13f,bold:Boolean=false)=TextView(this).apply{
        this.text=text
        textSize=size
        setTextColor(if(bold)accent else secondary)
        if(bold)typeface=Typeface.DEFAULT_BOLD
        setPadding(0,dp(9),0,dp(9))
    }

    private fun fileSignature():String{
        val files=listOf(OfflineVectorStore.mapFile(this),
            OfflineVectorStore.poiFile(this))
        return files.joinToString("|"){
            "${it.absolutePath}:${it.length()}:${it.lastModified()}"
        }
    }

    private fun checkExistingFiles(){
        if(checking)return
        checking=true
        lastSignature=fileSignature()
        checkStatus.text="Проверяю ранее скачанные карту Украины и адреса..."
        OfflineUkraineAssets.scanAsync(this){result->
            checking=false
            if(isDestroyed||isFinishing)return@scanAsync
            lastCheck=result
            val map=if(result.mapValid)
                "Найдена · ${size(result.mapSize)} · формат Mapsforge ✓"
            else result.mapError
            val poi=if(result.poiValid)
                "Найдена · ${size(result.poiSize)} · SQLite/индекс ✓"
            else result.poiError
            checkStatus.text="Карта Украины: $map\n"+
                "База адресов Украины: $poi"+
                (if(result.fullyAvailable)
                    "\nОба файла совместимы с этой версией — скачивать снова не нужно."
                else "\nМожно скачать только недостающий файл.")
            if(pendingCountryDownload){
                pendingCountryDownload=false
                downloadUkraine(OfflineCountryDownloadService.START_ALL)
            }
            otherStatus.text="Ранее сохранённые областные карты: "+
                result.otherMaps+" · адресные базы: "+result.otherPois+
                "\nЭти данные сохранены. Импорт и загрузка областей отключены."+
                (if(result.partialMap||result.partialPoi)
                    "\nЕсть незавершённая загрузка Украины: продолжение доступно." else "")
        }
    }

    private fun startDownload(action:String){
        try{
            startForegroundService(Intent(this,OfflineCountryDownloadService::class.java)
                .setAction(action))
            renderDownload()
        }catch(e:Exception){
            Toast.makeText(this,"Не удалось начать загрузку: "+
                (e.localizedMessage?:"ошибка Android"),Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadUkraine(action:String){
        val hasMap=lastCheck?.mapValid==true
        val hasPoi=lastCheck?.poiValid==true
        val already=when(action){
            OfflineCountryDownloadService.START_MAP->hasMap
            OfflineCountryDownloadService.START_POI->hasPoi
            else -> hasMap&&hasPoi
        }
        if(already){
            AlertDialog.Builder(this).setTitle("Уже сохранено")
                .setMessage("Нужные файлы из прошлой версии найдены и проверены. "+
                    "Повторная загрузка не требуется.")
                .setPositiveButton("Хорошо",null).show()
            return
        }
        val description=when(action){
            OfflineCountryDownloadService.START_MAP->"карту Украины (~833 МБ)"
            OfflineCountryDownloadService.START_POI->"базу адресов Украины (~167 МБ)"
            else->"недостающие файлы Украины (карта и адреса, до ~1 ГБ)"
        }
        AlertDialog.Builder(this).setTitle("Скачать $description?")
            .setMessage("Источник — открытый каталог Mapsforge, собственный "+
                "HTTPS-сервер не нужен. Проверенные сохранённые файлы "+
                "будут пропущены. Скачивание можно продолжить после обрыва.")
            .setPositiveButton("Скачать"){_,_->startDownload(action)}
            .setNegativeButton("Отмена",null).show()
    }

    private fun renderDownload(){
        val state=OfflineCountryDownloadService.progress
        downloadStatus.text=if(state.running)
            state.title+" · "+state.overallLabel+"\n"+
            "${size(state.downloaded)} / "+
            (if(state.total>0L)size(state.total) else "размер неизвестен")+
            "\n"+state.message
        else state.title+"\n"+state.message
        progressBar.isIndeterminate=state.running&&state.total<=0L
        progressBar.max=100
        progressBar.progress=if(state.total>0L)
            (state.downloaded*100L/state.total).coerceIn(0L,100L).toInt() else 0
    }

    private fun pickFile(request:Int){
        try{
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                addCategory(Intent.CATEGORY_OPENABLE)
                type="*/*"
            },request)
        }catch(e:Exception){
            Toast.makeText(this,"Не удалось открыть файлы: "+
                (e.localizedMessage?:"нет файлового приложения"),
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18),dp(14),dp(18),dp(14))
            setOnApplyWindowInsetsListener{v,insets->
                val bars=insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(dp(18)+bars.left,dp(14)+bars.top,
                    dp(18)+bars.right,dp(14)+bars.bottom)
                insets
            }
        }
        setContentView(ScrollView(this).apply{addView(root)})
        root.addView(label("Офлайн-карта Украины",23f,true))
        root.addView(label(
            "При обновлении поверх предыдущей версии скачанные файлы "+
            "сохраняются в памяти приложения. Проверка выполняется без интернета."))

        checkStatus=label("Проверяю файлы...",14f)
        checkStatus.setTextColor(primary)
        root.addView(checkStatus)
        otherStatus=label("",12f)
        root.addView(otherStatus)
        root.addView(button("Проверить сохранённые карту и адреса"){
            lastSignature=""
            checkExistingFiles()
        },LinearLayout.LayoutParams(-1,dp(48)))

        root.addView(label("СКАЧИВАНИЕ УКРАИНЫ",15f,true))
        root.addView(button("Скачать недостающие карту и адреса Украины"){
            downloadUkraine(OfflineCountryDownloadService.START_ALL)
        },LinearLayout.LayoutParams(-1,dp(56)))
        root.addView(button("Только карта Украины (.map)"){
            downloadUkraine(OfflineCountryDownloadService.START_MAP)
        },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(6)})
        root.addView(button("Только база адресов Украины (.poi)"){
            downloadUkraine(OfflineCountryDownloadService.START_POI)
        },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(6)})

        downloadStatus=label("Загрузка не запущена",13f)
        downloadStatus.setTextColor(primary)
        root.addView(downloadStatus)
        progressBar=ProgressBar(this,null,
            android.R.attr.progressBarStyleHorizontal)
        root.addView(progressBar,LinearLayout.LayoutParams(-1,dp(8)))
        root.addView(button("Остановить загрузку Украины"){
            if(OfflineCountryDownloadService.progress.running)
                startService(Intent(this,OfflineCountryDownloadService::class.java)
                    .setAction(OfflineCountryDownloadService.CANCEL))
            else Toast.makeText(this,"Нет активной загрузки",
                Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(-1,dp(44)))

        root.addView(label("ОФЛАЙН-КАРТА И ПОИСК АДРЕСОВ",15f,true))
        root.addView(button("Открыть карту Украины"){
            startActivity(Intent(this,OfflineVectorNavigatorActivity::class.java))
        },LinearLayout.LayoutParams(-1,dp(52)))
        root.addView(label(
            "Для поиска без интернета нужна база Mapsforge POI (.poi). "+
            "Её полнота зависит от адресов, внесённых в OpenStreetMap. "+
            "Построение нового автомобильного маршрута пока требует сети."))

        // Optional recovery from files in Downloads, NOT an oblast importer.
        root.addView(label("Дополнительно: восстановить файлы из памяти телефона",13f,true))
        root.addView(button("Выбрать готовую карту Украины (.map)"){
            pickFile(pickCountryMap)
        },LinearLayout.LayoutParams(-1,dp(49)))
        root.addView(button("Выбрать готовую базу адресов Украины (.poi)"){
            pickFile(pickCountryPois)
        },LinearLayout.LayoutParams(-1,dp(49)).apply{topMargin=dp(6)})
        root.addView(label(
            "Не удаляй приложение для обновления: удаление очищает его "+
            "внутренние файлы. Если обновление Android требует удаления "+
            "старой версии, сначала сохрани свои .map и .poi отдельно."))
        root.addView(button("Готово"){finish()},
            LinearLayout.LayoutParams(-1,dp(47)))
        renderDownload()
        // Defer a country download request until on-disk assets are checked.
        // Already downloaded files must never trigger a redundant popup.
        pendingCountryDownload=intent?.getBooleanExtra(
            "download_ukraine",false)==true
        checkExistingFiles()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK)return
        if(requestCode!=pickCountryMap&&requestCode!=pickCountryPois)return
        val uri:Uri=data?.data?:return
        val kind=if(requestCode==pickCountryMap)"map" else "poi"
        checkStatus.text="Проверяю файл и сохраняю его..."
        OfflineVectorStore.importFile(this,uri,kind,
            onProgress={checkStatus.text=it},
            onDone={result->
                if(isFinishing||isDestroyed)return@importFile
                result.fold(
                    onSuccess={message->
                        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
                        lastSignature=""
                        checkExistingFiles()
                    },
                    onFailure={error->
                        checkStatus.text="Не удалось импортировать файл: "+
                            (error.localizedMessage?:"неизвестная ошибка")
                    }
                )
            })
    }

    override fun onResume(){
        super.onResume()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }
    override fun onPause(){
        ui.removeCallbacks(tick)
        super.onPause()
    }
}
