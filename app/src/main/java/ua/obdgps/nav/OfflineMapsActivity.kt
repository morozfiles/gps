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
import android.widget.EditText
import android.widget.ProgressBar
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
    private val textColorPrimary=Color.rgb(238,242,248)
    private val muted=Color.rgb(170,191,210)
    private val pickMbtiles=4519
    private val pickVector=4520
    private val pickPoi=4521
    private lateinit var status:TextView
    private lateinit var detail:TextView
    private lateinit var downloadStatus:TextView
    private lateinit var packStatus:TextView
    private lateinit var progressBar:ProgressBar
    private val ui=Handler(Looper.getMainLooper())
    private val tick=object:Runnable{
        override fun run(){
            if(::downloadStatus.isInitialized)renderDownloadStatus()
            if(::packStatus.isInitialized)renderPackStatus()
            ui.postDelayed(this,900L)
        }
    }

    private fun dp(x:Int)=(resources.displayMetrics.density*x+.5f).toInt()

    private fun button(label:String,onClick:()->Unit):Button =
        Button(this).apply {
            text=label
            textSize=14f
            isAllCaps=false
            minHeight=0
            minimumHeight=0
            setTextColor(textColorPrimary)
            background=GradientDrawable().apply{
                cornerRadius=dp(10).toFloat()
                setColor(Color.rgb(47,54,65))
                setStroke(dp(1),Color.rgb(82,96,112))
            }
            setPadding(dp(9),dp(9),dp(9),dp(9))
            setOnClickListener {onClick()}
        }

    private fun pickVectorFile(code:Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type="*/*"
            },code)
        } catch (e:Exception) {
            Toast.makeText(this,"Не удалось открыть документы: "+
                (e.localizedMessage?:"нет файлового приложения"),
                Toast.LENGTH_LONG).show()
        }
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
        detail.text="Векторная карта: "+
            (if(OfflineVectorStore.hasMap(this))
                formatSize(OfflineVectorStore.mapFile(this).length()) else "не загружена")+
            "\nАдреса POI: "+
            (if(OfflineVectorStore.hasPois(this))
                formatSize(OfflineVectorStore.poiFile(this).length()) else "не загружены")+
            "\nПросмотренные карты: "+
            formatSize(OfflineMapStore.viewedSize(this))+
            "\nМесто хранения: память приложения"
    }

    private fun downloadUkraine(action:String) {
        val title=when(action) {
            OfflineCountryDownloadService.START_MAP -> "Только карта Украины"
            OfflineCountryDownloadService.START_POI -> "Только база адресов Украины"
            else -> "Украина: карта и адреса"
        }
        val approx=when(action) {
            OfflineCountryDownloadService.START_MAP -> "≈833 МБ"
            OfflineCountryDownloadService.START_POI -> "≈167 МБ"
            else -> "≈1 ГБ"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Скачать $approx из каталога Mapsforge? "+
                "Файлы сохранятся в памяти телефона, затем будут работать "+
                "офлайн. Для загрузки желательно использовать Wi-Fi. "+
                "Прерванное скачивание можно продолжить. "+
                "Предыдущая карта сохранится до завершения скачивания. "+
                "Автоматическая загрузка выполняется по твоему запросу, "+
                "а не при каждом запуске.")
            .setPositiveButton("Скачать"){_,_->
                try {
                    startForegroundService(Intent(this,
                        OfflineCountryDownloadService::class.java)
                        .setAction(action))
                    renderDownloadStatus()
                } catch (e:Exception) {
                    Toast.makeText(this,"Не удалось начать загрузку: "+
                        (e.localizedMessage?:"ошибка Android"),
                        Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Отмена",null).show()
    }

    private fun renderDownloadStatus() {
        val state=OfflineCountryDownloadService.progress
        val downloaded=state.downloaded/1048576L
        val total=state.total/1048576L
        val value=if(state.running)
            state.title+" · "+state.overallLabel+
            "\n"+downloaded+" / "+
                (if(state.total>0L)"$total МБ" else "? МБ")+
            (if(state.total>0L)
                " ("+(state.downloaded*100L/state.total).coerceIn(0,100)+"%)"
             else "")+
            "\n"+state.message
        else state.title+"\n"+state.message
        downloadStatus.text=value
        progressBar.isIndeterminate=state.running&&state.total<=0L
        progressBar.max=100
        progressBar.progress=if(state.total>0L)
            (state.downloaded*100L/state.total).coerceIn(0,100).toInt() else 0
        if(!state.running) {
            val map=OfflineVectorStore.hasMap(this)
            val pois=OfflineVectorStore.hasPois(this)
            if(map || pois) {
                detail.text="Векторная карта: "+
                    (if(map)formatSize(OfflineVectorStore.mapFile(this).length())
                     else "не загружена")+
                    "\nАдреса POI: "+
                    (if(pois)formatSize(OfflineVectorStore.poiFile(this).length())
                     else "не загружены")+
                    "\nПросмотренные участки: "+
                    formatSize(OfflineMapStore.viewedSize(this))
            }
        }
    }


    private fun renderPackStatus(){
        if(!::packStatus.isInitialized)return
        val id=OfflineRegionPacks.selected(this)
        val current=OfflineRegionPacks.region(id)?.name?:id
        val saved=OfflineRegionPacks.mapFiles(this)
        val progress=OfflineRegionDownloadService.progress
        packStatus.text="Выбрано: $current"+
            "\\nОбзор Украины: "+
            (if(OfflineRegionPacks.has(this,OfflineRegionPacks.OVERVIEW))
                "сохранён" else "не загружен")+
            "\\nПодробная карта: "+
            (if(OfflineRegionPacks.has(this,id))"сохранена" else "не загружена")+
            "\\nБаза адресов области: "+
            (if(OfflineRegionPacks.has(this,id,"poi"))"сохранена" else "не загружена")+
            "\\nДоступных файлов карты: "+saved.size+
            "\\nСервер карт: "+(if(OfflineRegionPacks.baseUrl(this).isNotBlank())
                "настроен" else "не задан")+
            "\\n"+progress.title+
            (if(progress.running)" · ${progress.downloaded/1048576} / "+
                (if(progress.total>0)"${progress.total/1048576}" else "?")+" МБ"
                else "")+
            (if(progress.message.isNotBlank())"\\n"+progress.message else "")
    }

    private fun chooseRegion(){
        val choices=OfflineRegionPacks.regions.map{region->
            region.name+(if(OfflineRegionPacks.has(this,region.id))
                " ✓" else "")
        }.toTypedArray()
        val selected=OfflineRegionPacks.regions.indexOfFirst{
            it.id==OfflineRegionPacks.selected(this)}
        AlertDialog.Builder(this).setTitle("Подробные карты областей")
            .setSingleChoiceItems(choices,selected){dialog,which->
                val region=OfflineRegionPacks.regions[which]
                OfflineRegionPacks.select(this,region.id)
                dialog.dismiss()
                renderPackStatus()
                if(!OfflineRegionPacks.has(this,region.id))
                    AlertDialog.Builder(this).setTitle(region.name)
                        .setMessage("Подробная карта не сохранена. Скачать её и "+
                            "локальный адресный индекс по запросу?")
                        .setPositiveButton("Скачать"){_,_->
                            downloadPack(OfflineRegionDownloadService.DOWNLOAD_REGION)}
                        .setNegativeButton("Позже",null).show()
            }.setNegativeButton("Закрыть",null).show()
    }

    private fun showPackSettings(){
        val field=EditText(this).apply{
            hint="https://your-map-server.example/maps"
            setSingleLine(true)
            setText(OfflineRegionPacks.baseUrl(this@OfflineMapsActivity))
        }
        val wifi=android.widget.CheckBox(this).apply{
            text="Загрузка только через Wi-Fi"
            isChecked=OfflineRegionPacks.wifiOnly(this@OfflineMapsActivity)
            setTextColor(textColorPrimary)
        }
        val auto=android.widget.CheckBox(this).apply{
            text="При запуске автоматически загрузить обзор и Киевскую область"
            isChecked=OfflineRegionPacks.autoDownload(this@OfflineMapsActivity)
            setTextColor(textColorPrimary)
        }
        val container=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(7),dp(18),0)
            addView(TextView(this@OfflineMapsActivity).apply{
                text="Каталог подготовленных Mapsforge-файлов. Сервер должен "+
                    "предоставлять overview.map, kyiv.map, kyiv.poi и файлы "+
                    "других областей по тому же шаблону. Без адреса сервера "+
                    "автоматическое скачивание недоступно."
                setTextColor(textColorPrimary)
                textSize=13f
            })
            addView(field)
            addView(wifi)
            addView(auto)
        }
        AlertDialog.Builder(this).setTitle("Источник офлайн-карт")
            .setView(container).setPositiveButton("Сохранить"){_,_->
                try{
                    OfflineRegionPacks.setBaseUrl(this,field.text.toString())
                    OfflineRegionPacks.setWifiOnly(this,wifi.isChecked)
                    OfflineRegionPacks.setAutoDownload(this,auto.isChecked)
                    renderPackStatus()
                    if(OfflineRegionPacks.needsDefaultPacks(this))
                        downloadPack(OfflineRegionDownloadService.AUTO)
                }catch(e:Exception){
                    Toast.makeText(this,e.localizedMessage,Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Отмена",null).show()
    }

    private fun downloadPack(action:String) {
        if(OfflineRegionPacks.baseUrl(this).isBlank()){
            AlertDialog.Builder(this).setTitle("Нужен сервер карт")
                .setMessage("Для загрузки областей требуются готовые .map и .poi "+
                    "на HTTPS-сервере. Пока его нет, можно импортировать "+
                    "файлы или скачать общую карту Украины из Mapsforge.")
                .setPositiveButton("Настроить"){_,_->showPackSettings()}
                .setNegativeButton("Закрыть",null).show()
            return
        }
        try{
            val id=OfflineRegionPacks.selected(this)
            startForegroundService(Intent(this,OfflineRegionDownloadService::class.java)
                .setAction(action)
                .putExtra(OfflineRegionDownloadService.REGION_ID,id))
            renderPackStatus()
        }catch(e:Exception){
            Toast.makeText(this,"Не удалось начать загрузку: "+
                (e.localizedMessage?:"ошибка"),Toast.LENGTH_LONG).show()
        }
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
            setTextColor(textColorPrimary)
            setPadding(0,0,0,dp(15))
        })
        status=TextView(this).apply{
            setTextColor(textColorPrimary);textSize=16f
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
            text="ОБЛАСТИ УКРАИНЫ · ВЕКТОРНЫЕ КАРТЫ"
            textSize=15f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(137,190,250))
            setPadding(0,dp(16),0,dp(9))
        })
        packStatus=TextView(this).apply{
            setTextColor(textColorPrimary)
            textSize=13f
            setPadding(0,dp(7),0,dp(8))
        }
        root.addView(packStatus)
        root.addView(button("Выбрать область для подробной карты"){
            chooseRegion()
        },LinearLayout.LayoutParams(-1,dp(55)))
        root.addView(button("Скачать подробную карту выбранной области"){
            downloadPack(OfflineRegionDownloadService.DOWNLOAD_REGION)
        },LinearLayout.LayoutParams(-1,dp(55)).apply{topMargin=dp(6)})
        root.addView(button("Скачать обзорную карту всей Украины"){
            downloadPack(OfflineRegionDownloadService.DOWNLOAD_OVERVIEW)
        },LinearLayout.LayoutParams(-1,dp(51)).apply{topMargin=dp(6)})
        root.addView(button("Настроить источник, Wi-Fi и автозагрузку"){
            showPackSettings()
        },LinearLayout.LayoutParams(-1,dp(51)).apply{topMargin=dp(6)})
        root.addView(button("Остановить загрузку областей"){
            if(OfflineRegionDownloadService.progress.running)
                startService(Intent(this,OfflineRegionDownloadService::class.java)
                    .setAction(OfflineRegionDownloadService.STOP))
            else Toast.makeText(this,"Нет активной загрузки",Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(6)})
        root.addView(button("Удалить выбранную область из памяти"){
            val id=OfflineRegionPacks.selected(this)
            AlertDialog.Builder(this).setTitle("Удалить "+
                (OfflineRegionPacks.region(id)?.name?:id)+"?")
                .setMessage("Удалится только эта подробная карта и её база адресов. "+
                    "Остальные области и обзор останутся на телефоне.")
                .setPositiveButton("Удалить"){_,_->
                    OfflineRegionPacks.delete(this,id)
                    renderPackStatus()
                }.setNegativeButton("Отмена",null).show()
        },LinearLayout.LayoutParams(-1,dp(47)).apply{topMargin=dp(6)})
        root.addView(TextView(this).apply{
            text="Обзор Украины хранится отдельно от областей. Киевская область "+
                "выбрана по умолчанию. Файлы не скачиваются повторно при "+
                "запуске, если уже сохранены. Другие области загружаются "+
                "только по запросу. Для региональных файлов нужен "+
                "настроенный HTTPS-каталог; готового публичного каталога "+
                "в приложении пока нет."
            textSize=12f
            setTextColor(muted)
            setPadding(0,dp(8),0,dp(16))
        })
        renderPackStatus()

        root.addView(TextView(this).apply {
            text="СКАЧАТЬ С ИНТЕРНЕТА"
            textSize=15f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(137,190,250))
            setPadding(0,dp(13),0,dp(10))
        })
        root.addView(button("Скачать всю Украину · карта + адреса (~1 ГБ)") {
            downloadUkraine(OfflineCountryDownloadService.START_ALL)
        },LinearLayout.LayoutParams(-1,dp(58)))
        root.addView(button("Только карту Украины (~833 МБ)") {
            downloadUkraine(OfflineCountryDownloadService.START_MAP)
        },LinearLayout.LayoutParams(-1,dp(49)).apply{
            topMargin=dp(7)
        })
        root.addView(button("Только базу адресов Украины (~167 МБ)") {
            downloadUkraine(OfflineCountryDownloadService.START_POI)
        },LinearLayout.LayoutParams(-1,dp(49)).apply{
            topMargin=dp(7)
        })
        downloadStatus=TextView(this).apply {
            textSize=14f
            setTextColor(textColorPrimary)
            setPadding(0,dp(12),0,dp(2))
        }
        root.addView(downloadStatus)
        progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal)
        root.addView(progressBar,LinearLayout.LayoutParams(-1,dp(8)).apply{
            bottomMargin=dp(9)
        })
        root.addView(button("Остановить скачивание (можно продолжить)") {
            if(OfflineCountryDownloadService.progress.running) {
                startService(Intent(this,OfflineCountryDownloadService::class.java)
                    .setAction(OfflineCountryDownloadService.CANCEL))
            } else Toast.makeText(this,"Загрузка не запущена",
                Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(-1,dp(43)))
        root.addView(TextView(this).apply {
            text="Карта Украины включает Киев и все области. "+
                "Для небольшого комплекта Киева и области оставлен "+
                "импорт ранее подготовленных файлов .map и .poi. "+
                "Скачивание использует Mapsforge, не сервер тайлов OpenStreetMap."
            textSize=12f
            setTextColor(muted)
            setPadding(0,dp(7),0,dp(12))
        })
        root.addView(TextView(this).apply{
            text="ВЕКТОРНЫЕ КАРТЫ — светлая автомобильная тема"
            textSize=15f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(137,190,250))
            setPadding(0,dp(20),0,dp(10))
        })
        root.addView(button("Импортировать карту региона (.map)") {
            pickVectorFile(pickVector)
        },LinearLayout.LayoutParams(-1,dp(55)))
        root.addView(button("Импортировать адреса (.poi)") {
            pickVectorFile(pickPoi)
        },LinearLayout.LayoutParams(-1,dp(55)).apply{
            topMargin=dp(7)
        })
        root.addView(button("Открыть векторную офлайн-карту") {
            startActivity(Intent(this,OfflineVectorNavigatorActivity::class.java))
        },LinearLayout.LayoutParams(-1,dp(55)).apply{
            topMargin=dp(7)
        })
        root.addView(TextView(this).apply{
            text="Для векторного режима нужны файлы Mapsforge: "+
                ".map (дороги и подписи) и .poi (локальный поиск мест). "+
                "Поиск по POI зависит от полноты исходных адресов; "+
                "встречаются улицы и дома, которые не внесены в OpenStreetMap."
            textSize=13f
            setTextColor(muted)
            setPadding(0,dp(9),0,dp(6))
        })
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
                "Поиск адресов работает без интернета после загрузки базы .poi; " +
                "построение нового маршрута по-прежнему требует интернета."
            textSize=13f
            setTextColor(muted)
            setPadding(0,dp(19),0,dp(14))
        })
        root.addView(button("Готово"){finish()},
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(46)))
        updateStatus()
        renderDownloadStatus()
        if(intent?.getBooleanExtra("download_ukraine",false)==true)
            downloadUkraine(OfflineCountryDownloadService.START_ALL)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK)return
        val uri:Uri=data?.data?:return
        if(requestCode==pickVector || requestCode==pickPoi) {
            val kind=if(requestCode==pickVector)"map" else "poi"
            status.text="Проверяю и копирую файл..."
            OfflineVectorStore.importFile(this,uri,kind,
                onProgress={status.text=it},
                onDone={result->
                    if(isFinishing || isDestroyed)return@importFile
                    result.fold(
                        onSuccess={message->
                            Toast.makeText(this,message,Toast.LENGTH_LONG).show()
                            updateStatus()
                        },
                        onFailure={error->
                            status.text="Ошибка файла: "+
                                (error.localizedMessage?:"неизвестная")
                        }
                    )
                })
            return
        }
        if(requestCode!=pickMbtiles)return
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
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onPause(){
        ui.removeCallbacks(tick)
        super.onPause()
    }
}
