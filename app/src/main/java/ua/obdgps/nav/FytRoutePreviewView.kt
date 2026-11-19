package ua.obdgps.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.max

/**
 * Lightweight route-preview canvas designed for an AOSP FYT head unit.
 * It does not load tiles, Google Play services or external maps at startup.
 * This is clearly labelled a schematic, not a street-level road map.
 */
class FytRoutePreviewView(context:Context):View(context){
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridColor=Color.rgb(44,58,72)
    private val routeColor=Color.rgb(98,192,245)
    private val foreground=Color.rgb(230,241,252)
    private val secondary=Color.rgb(160,184,204)
    private val current=Color.rgb(67,229,170)
    private val destColor=Color.rgb(253,122,106)
    private fun dp(n:Float)=n*density
    private var route:RoutingEngine.Route?=null
    private var dest:RoutingEngine.Point?=null
    private var active=false
    private var fix:State.NavFix?=null

    fun update(session:FytRouteStore.Session?,position:State.NavFix?){
        route=session?.route
        dest=session?.destination
        active=session?.active==true
        val fresh=position?.elapsedMs?.let{
            SystemClock.elapsedRealtime()-it in 0L..15_000L
        }==true
        fix=if(fresh)position else null
        invalidate()
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val w=width.toFloat();val h=height.toFloat()
        if(w<10f||h<10f)return
        paint.style=Paint.Style.FILL
        paint.color=Color.rgb(25,38,52)
        canvas.drawRoundRect(RectF(0f,0f,w,h),dp(13f),dp(13f),paint)
        paint.color=gridColor
        paint.strokeWidth=dp(1f)
        for(i in 1..13){
            val x=i*w/14f
            canvas.drawLine(x,0f,x,h,paint)
        }
        for(i in 1..8){
            val y=i*h/9f
            canvas.drawLine(0f,y,w,y,paint)
        }
        paint.style=Paint.Style.FILL
        paint.color=secondary
        paint.textSize=dp(12f)
        canvas.drawText("СХЕМА МАРШРУТА  •  нажми, чтобы открыть карту",
            dp(15f),dp(26f),paint)

        val geo=route?.points ?: emptyList()
        val d=dest
        if(geo.size<2){
            paint.color=foreground
            paint.textSize=dp(21f)
            paint.isFakeBoldText=true
            canvas.drawText(if(d==null)"Выбери маршрут на карте"
                else "Цель сохранена",dp(24f),h*0.49f,paint)
            paint.isFakeBoldText=false
            paint.textSize=dp(14f)
            paint.color=secondary
            canvas.drawText(if(d==null)"GM906BT · встроенный GNSS · OBD"
                else "Открой карту для построения маршрута",
                dp(24f),h*0.49f+dp(30f),paint)
            return
        }
        // Project a saved route into canvas, constrained by a maximum
        // of 900 vertices on the UI thread.
        val span=geo.size
        val step=max(1,span/900)
        val sample=geo.filterIndexed{i,_->i%step==0}.toMutableList()
        if(sample.last()!=geo.last())sample.add(geo.last())
        val midLat=(sample.minOf{it.lat}+sample.maxOf{it.lat})/2.0
        val lonFactor=cos(Math.toRadians(midLat)).coerceAtLeast(0.10)
        val xs=sample.map{it.lon*lonFactor}
        val ys=sample.map{it.lat}
        val minX=xs.minOrNull()!!;val maxX=xs.maxOrNull()!!
        val minY=ys.minOrNull()!!;val maxY=ys.maxOrNull()!!
        val dx=(maxX-minX).coerceAtLeast(0.0003)
        val dy=(maxY-minY).coerceAtLeast(0.0003)
        val padX=dp(34f);val padTop=dp(50f);val padBottom=dp(34f)
        val areaW=(w-padX*2).coerceAtLeast(10f)
        val areaH=(h-padTop-padBottom).coerceAtLeast(10f)
        val scale=minOf(areaW/dx,areaH/dy)
        val usedW=(dx*scale).toFloat()
        val usedH=(dy*scale).toFloat()
        val offsetX=(w-usedW)/2f
        val offsetY=padTop+(areaH-usedH)/2f
        fun point(lat:Double,lon:Double):Pair<Float,Float>{
            return (offsetX+((lon*lonFactor-minX)*scale).toFloat()) to
                (offsetY+usedH-((lat-minY)*scale).toFloat())
        }
        val path=Path()
        sample.forEachIndexed{i,p->
            val (x,y)=point(p.lat,p.lon)
            if(i==0)path.moveTo(x,y) else path.lineTo(x,y)
        }
        paint.style=Paint.Style.STROKE
        paint.strokeJoin=Paint.Join.ROUND
        paint.strokeCap=Paint.Cap.ROUND
        paint.color=Color.rgb(35,82,107);paint.strokeWidth=dp(10f)
        canvas.drawPath(path,paint)
        paint.color=routeColor;paint.strokeWidth=dp(5f)
        canvas.drawPath(path,paint)
        paint.style=Paint.Style.FILL
        val end=sample.last()
        val (tx,ty)=point(end.lat,end.lon)
        paint.color=destColor
        canvas.drawCircle(tx,ty,dp(10f),paint)
        paint.color=Color.WHITE
        canvas.drawCircle(tx,ty,dp(3f),paint)
        val gps=fix
        if(gps!=null){
            val (cx,cy)=point(gps.lat,gps.lon)
            if(cx in 0f..w && cy in 0f..h){
                paint.color=current
                canvas.drawCircle(cx,cy,dp(10f),paint)
                paint.color=Color.rgb(19,45,50)
                canvas.drawCircle(cx,cy,dp(4f),paint)
            }
        }
        paint.color=foreground;paint.textSize=dp(15f)
        canvas.drawText(String.format(java.util.Locale.US,
            "%.1f км  •  %s",route!!.lengthKm,
            if(active)"ведение активно" else "маршрут сохранён"),
            dp(17f),h-dp(14f),paint)
    }
}
