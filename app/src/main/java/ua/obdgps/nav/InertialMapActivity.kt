package ua.obdgps.nav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Test map reads ONLY inertial locations written by NavService's DeadReckoner.
 * This Activity never obtains GPS, Fused Location, or phone location.
 */
class InertialMapActivity : Activity() {
    private val pageBackgroundColor=Color.rgb(28,33,40)
    private val surface=Color.rgb(51,59,70)
    private val primaryTextColor=Color.rgb(236,242,249)
    private val secondary=Color.rgb(180,193,209)
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var map: InertialTrackMapView
    private lateinit var info: TextView
    private fun dp(n:Int)=(resources.displayMetrics.density*n+0.5f).toInt()

    private fun button(label:String, action:()->Unit): Button = Button(this).apply {
        text=label
        isAllCaps=false
        textSize=14f
        setTextColor(primaryTextColor)
        setBackgroundColor(surface)
        setOnClickListener { action() }
    }

    private val refresh=object: Runnable {
        override fun run() {
            val point=State.inertialPoint
            val trail=State.inertialTrail.toList()
            map.updateFromInertial(point,trail)
            if(point==null) {
                info.text="Нет точек DR. Запусти ведение по датчикам на главном экране."
            } else {
                val old=SystemClock.elapsedRealtime()-point.elapsedMs>5000L
                val speed=String.format(Locale.getDefault(),"%.1f",point.speedKmh)
                val lat=String.format(Locale.US,"%.6f",point.latitude)
                val lon=String.format(Locale.US,"%.6f",point.longitude)
                val mode=if(State.dr && !old)"DR работает" else "Последняя точка DR"
                info.text=mode+" · "+speed+" км/ч · "+point.bearing.toInt()+"°\n"+
                    lat+", "+lon+" · точек: "+trail.size
            }
            handler.postDelayed(this,1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=pageBackgroundColor
        window.navigationBarColor=pageBackgroundColor
        window.decorView.systemUiVisibility=0

        val column=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor)
            setPadding(dp(12),dp(7),dp(12),dp(8))
            setOnApplyWindowInsetsListener { view,insets ->
                val bars=insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left+dp(12),bars.top+dp(7),
                    bars.right+dp(12),bars.bottom+dp(8))
                insets
            }
        }
        val titleRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text="Тестовая карта · DR"
            textSize=20f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(primaryTextColor)
        },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        titleRow.addView(button("Назад") { finish() },
            LinearLayout.LayoutParams(dp(82),dp(44)))
        column.addView(titleRow)

        info=TextView(this).apply {
            setTextColor(secondary)
            textSize=13f
            setPadding(dp(2),dp(8),dp(2),dp(10))
            text="Ожидание расчётных координат..."
        }
        column.addView(info)
        val container=FrameLayout(this)
        map=InertialTrackMapView(this)
        container.addView(map,FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        val controls=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(4),dp(4),dp(4),dp(4))
        }
        controls.addView(button("+") { map.changeZoom(1) },
            LinearLayout.LayoutParams(dp(46),dp(46)))
        controls.addView(button("−") { map.changeZoom(-1) },
            LinearLayout.LayoutParams(dp(46),dp(46)).apply { topMargin=dp(5) })
        controls.addView(button("◎") { map.followCar() },
            LinearLayout.LayoutParams(dp(46),dp(46)).apply { topMargin=dp(5) })
        container.addView(controls,FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.RIGHT).apply {setMargins(dp(4),dp(12),dp(8),0)})
        column.addView(container,LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,0,1f))

        val legend=TextView(this).apply {
            text="● Голубой — расчёт DR    ━ Бирюзовый — маршрут\n"+
                 "GPS для маркера не используется · без сети остаётся сетка"
            setTextColor(secondary)
            textSize=12f
            gravity=Gravity.CENTER
            setPadding(0,dp(8),0,dp(4))
        }
        column.addView(legend)
        val attribution=TextView(this).apply {
            text="© OpenStreetMap contributors · Нажми для лицензии"
            setTextColor(Color.rgb(145,194,242))
            textSize=11f
            gravity=Gravity.CENTER
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.openstreetmap.org/copyright")))
            }
        }
        column.addView(attribution)
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}

/** Visible OSM raster tiles, seven-day disk caching, and a local-grid fallback. */
private class InertialTrackMapView(context:Context): View(context) {
    private val tilePaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val gridPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(65,75,87);strokeWidth=1f
    }
    private val routeOutline=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(6,32,44);strokeWidth=11f;style=Paint.Style.STROKE
        strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
    }
    private val routePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(22,207,206);strokeWidth=6f;style=Paint.Style.STROKE
        strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
    }
    private val pinHalo=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(10,33,49);style=Paint.Style.FILL
    }
    private val pinFill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.rgb(36,170,255);style=Paint.Style.FILL
    }
    private val cache=object:LruCache<String,Bitmap>(24*1024) {
        override fun sizeOf(key:String,value:Bitmap)=max(1,value.byteCount/1024)
    }
    private val loading=HashSet<String>()
    private val failedUntil=HashMap<String,Long>()
    private val worker=Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable,"Visible-OSM-Tiles").apply{isDaemon=true}
    }
    private val tileDir=File(context.cacheDir,"inertial-test-tiles").apply{mkdirs()}
    private val handler=Handler(Looper.getMainLooper())
    private var current:State.TrailPoint?=null
    private var history=emptyList<State.TrailPoint>()
    private var follow=true
    private var centerLat=50.4501 // Static map start, not user GPS.
    private var centerLon=30.5234
    private var zoom=16
    private var touchX=0f
    private var touchY=0f

    fun updateFromInertial(point:State.TrailPoint?,trail:List<State.TrailPoint>) {
        current=point;history=trail
        if(follow && point!=null) {
            centerLat=point.latitude;centerLon=point.longitude
        }
        invalidate()
    }

    fun followCar() {
        follow=true
        current?.let{centerLat=it.latitude;centerLon=it.longitude}
        invalidate()
    }

    fun changeZoom(amount:Int) {
        zoom=(zoom+amount).coerceIn(12,18)
        invalidate()
    }

    private fun worldX(lon:Double)=((lon+180.0)/360.0)*256.0*(1 shl zoom)
    private fun worldY(lat:Double):Double {
        val a=Math.toRadians(lat.coerceIn(-85.051128,85.051128))
        return (1.0-ln(tan(a)+1.0/cos(a))/Math.PI)/2.0*256.0*(1 shl zoom)
    }
    private fun point(lat:Double,lon:Double):Pair<Float,Float> {
        val world=256.0*(1 shl zoom)
        var dx=worldX(lon)-worldX(centerLon)
        if(dx>world/2)dx-=world
        if(dx< -world/2)dx+=world
        return Pair((width/2.0+dx).toFloat(),
            (height/2.0+worldY(lat)-worldY(centerLat)).toFloat())
    }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(34,40,48))
        for(x in 0..(width/80))canvas.drawLine(x*80f,0f,x*80f,height.toFloat(),gridPaint)
        for(y in 0..(height/80))canvas.drawLine(0f,y*80f,width.toFloat(),y*80f,gridPaint)
        if(width<=0 || height<=0)return
        val n=1 shl zoom
        val ox=worldX(centerLon)-width/2.0
        val oy=worldY(centerLat)-height/2.0
        val left=floor(ox/256.0).toInt()
        val right=floor((ox+width)/256.0).toInt()
        val top=floor(oy/256.0).toInt()
        val bottom=floor((oy+height)/256.0).toInt()
        for(y in top..bottom) {
            if(y<0 || y>=n)continue
            for(rawX in left..right) {
                val x=((rawX%n)+n)%n
                val sx=(rawX*256.0-ox).toFloat()
                val sy=(y*256.0-oy).toFloat()
                val image=tileBitmap(zoom,x,y)
                if(image!=null)
                    canvas.drawBitmap(image,null,RectF(sx,sy,sx+256f,sy+256f),tilePaint)
            }
        }
        if(history.size>=2) {
            val line=Path()
            var first=true
            var previousX=0f;var previousY=0f
            for(entry in history) {
                val (x,y)=point(entry.latitude,entry.longitude)
                if(first || abs(x-previousX)>width*.8f || abs(y-previousY)>height*.8f) {
                    line.moveTo(x,y);first=false
                } else line.lineTo(x,y)
                previousX=x;previousY=y
            }
            canvas.drawPath(line,routeOutline)
            canvas.drawPath(line,routePaint)
        }
        current?.let {
            val (x,y)=point(it.latitude,it.longitude)
            canvas.drawCircle(x,y,24f,pinHalo)
            canvas.drawCircle(x,y,18f,pinFill)
            canvas.save()
            canvas.rotate(it.bearing.toFloat(),x,y)
            val arrow=Path().apply {
                moveTo(x,y-30f);lineTo(x-10f,y-12f);lineTo(x+10f,y-12f);close()
            }
            canvas.drawPath(arrow,pinFill)
            canvas.restore()
        }
    }

    private fun tileBitmap(z:Int,x:Int,y:Int):Bitmap? {
        val key=z.toString()+"_"+x+"_"+y
        cache.get(key)?.let{return it}
        val file=File(tileDir,key+".png")
        if(file.exists() && file.length()>0) {
            val img=BitmapFactory.decodeFile(file.absolutePath)
            if(img!=null) {cache.put(key,img);return img}
            file.delete()
        }
        val now=SystemClock.elapsedRealtime()
        synchronized(loading) {
            if(key in loading || loading.size>=22 || (failedUntil[key]?:0L)>now) return null
            loading.add(key)
        }
        worker.execute {
            try {
                downloadTile(z,x,y,file)
                val img=BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IllegalStateException("Tile decode failed")
                cache.put(key,img)
                handler.post {invalidate()}
            } catch (_:Exception) {
                synchronized(loading){failedUntil[key]=SystemClock.elapsedRealtime()+60_000L}
            } finally {
                synchronized(loading){loading.remove(key)}
            }
        }
        return null
    }

    private fun downloadTile(z:Int,x:Int,y:Int,file:File) {
        val url="https://tile.openstreetmap.org/"+z+"/"+x+"/"+y+".png"
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=6000
        connection.readTimeout=7000
        connection.useCaches=true
        connection.setRequestProperty("User-Agent",
            "OBD-GPS-Navigator/0.8.0 Android diagnostic (+https://github.com/morozfiles)")
        connection.setRequestProperty("Accept","image/png")
        try {
            if(connection.responseCode!=200)throw IllegalStateException("Tile HTTP error")
            if(!connection.contentType.orEmpty().startsWith("image/"))
                throw IllegalStateException("Not image")
            val tmp=File(tileDir,file.name+".part")
            try {
                connection.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val bytes=ByteArray(8192)
                        var total=0
                        while(true) {
                            val n=input.read(bytes)
                            if(n<0)break
                            total+=n
                            if(total>2_000_000)throw IllegalStateException("Oversized tile")
                            out.write(bytes,0,n)
                        }
                    }
                }
                if(!tmp.renameTo(file)) {
                    file.writeBytes(tmp.readBytes())
                    tmp.delete()
                }
            } finally {tmp.delete()}
        } finally {connection.disconnect()}
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX=event.x;touchY=event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx=event.x-touchX
                val dy=event.y-touchY
                if(abs(dx)>2f || abs(dy)>2f) {
                    follow=false
                    val world=256.0*(1 shl zoom)
                    val nx=(worldX(centerLon)-dx+world)%world
                    val ny=(worldY(centerLat)-dy).coerceIn(1.0,world-1.0)
                    centerLon=nx/world*360.0-180.0
                    centerLat=Math.toDegrees(atan(sinh(Math.PI*(1.0-2.0*ny/world))))
                    invalidate()
                }
                touchX=event.x;touchY=event.y
                return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}
