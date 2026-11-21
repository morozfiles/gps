package ua.obdgps.nav

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import java.util.Locale
import kotlin.math.*

/**
 * Phone-only Google basemap + official Routes API. Location is provided
 * by our existing NavService, not Google Maps' my-location blue dot.
 * In DR mode the planned route constrains a display-only OBD estimate.
 */
class GoogleDrMapActivity:Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private val client=GoogleRouteClient()
    private lateinit var mapView:MapView
    private var map:GoogleMap?=null
    private lateinit var status:TextView
    private lateinit var directions:TextView
    private var destination:RoutingEngine.Point?=null
    private var route:RoutingEngine.Route?=null
    private var line:Polyline?=null
    private var car:Marker?=null
    private var goal:Marker?=null
    private var active=false
    private var requesting=false
    private var lastRaw:State.NavFix?=null
    private val tracker=RouteOdometer()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        status=TextView(this).apply{
            text="Google Maps · выбери цель нажатием на карту"
            textSize=15f;setPadding(16,12,16,8)
        }
        directions=TextView(this).apply{
            text="Расчётное положение при потере GPS · OBD + гироскоп + сеть"
            setPadding(16,5,16,9)
        }
        root.addView(status);root.addView(directions)
        mapView=MapView(this)
        mapView.onCreate(savedInstanceState)
        root.addView(mapView,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun button(text:String,click:()->Unit) {
            controls.addView(Button(this).apply{
                this.text=text;isAllCaps=false;setOnClickListener{click()}
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        button("Маршрут"){plan()}
        button("Начать"){startGuidance()}
        button("Стоп"){stopGuidance()}
        root.addView(controls)
        setContentView(root)
        mapView.getMapAsync{google->
            map=google
            google.uiSettings.isMyLocationButtonEnabled=false
            google.uiSettings.isCompassEnabled=true
            google.setOnMapClickListener{p->
                if(active)stopGuidance()
                destination=RoutingEngine.Point(p.latitude,p.longitude)
                goal?.remove()
                goal=google.addMarker(MarkerOptions().position(p).title("Цель маршрута"))
                status.text="Цель выбрана. Нажми «Маршрут»"
            }
            val initial=State.navFix
            google.moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(initial?.lat?:50.4501,initial?.lon?:30.5234),14f))
        }
    }
    private fun plan() {
        val to=destination?:run{status.text="Выбери точку на карте";return}
        val fix=State.navFix?:run{status.text="Нет начальных координат. Задай старт в навигаторе";return}
        if(requesting)return
        requesting=true
        status.text="Google строит маршрут..."
        client.request(RoutingEngine.Point(fix.lat,fix.lon),to){result->
            requesting=false
            if(isFinishing || isDestroyed)return@request
            result.fold(onSuccess={r->
                route=r;tracker.reset()
                line?.remove()
                line=map?.addPolyline(PolylineOptions()
                    .addAll(r.points.map{LatLng(it.lat,it.lon)})
                    .color(Color.rgb(24,118,242)).width(14f))
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(r.points.first().lat,r.points.first().lon),14f))
                status.text="Маршрут Google: "+
                    String.format(Locale.getDefault(),"%.1f км",r.lengthKm)
                directions.text="Нажми «Начать» для ведения"
            },onFailure={e->
                status.text="Google Routes: "+(e.localizedMessage?:"Ошибка")
            })
        }
    }
    private fun startGuidance(){
        val r=route?:run{status.text="Сначала построй маршрут";return}
        active=true
        tracker.bind(r,State.navFix)
        State.activeRouting=true
        State.gpsFilterRoutePoints=r.points
        val first=r.points.first()
        val bearing=routeBearing(r.points[0],r.points[1])
        State.routeDrSeed=State.NavFix(first.lat,first.lon,bearing,
            90f,"Старт маршрута",SystemClock.elapsedRealtime())
        if(!State.dr && !freshTrustedGps()) {
            try{startForegroundService(Intent(this,NavService::class.java)
                .setAction(NavService.START_ROUTE_DR))}
            catch(_:Exception){status.text="Не удалось запустить датчики"}
        }
        status.text="Google · ведение включено"
    }
    private fun stopGuidance(){
        active=false
        tracker.reset()
        State.activeRouting=false
        State.gpsFilterRoutePoints=emptyList()
        State.routeDrSeed=null
        status.text="Ведение остановлено"
    }
    private fun freshTrustedGps():Boolean {
        val f=State.navFix?:return false
        return f.source=="GPS" && f.accuracy in 0f..30f &&
            SystemClock.elapsedRealtime()-f.elapsedMs in 0L..5000L &&
            (State.gpsSpeedKmh?:0.0)<=180.0
    }
    private val refresh=object:Runnable {
        override fun run(){
            val now=SystemClock.elapsedRealtime()
            val raw=State.navFix?.takeIf{now-it.elapsedMs in 0L..12000L}
            val r=route
            var visible=raw
            if(active && r!=null){
                val obd=State.obdSpeedKmh()?.takeIf{
                    State.speedUpdatedElapsedMs>0L &&
                    now-State.speedUpdatedElapsedMs in 0L..3500L &&
                    it.isFinite() && it in 0.0..180.0
                }
                // Independent OBD/gyro route cursor, plus cautious position
                // corrections from NavService's accepted GPS/network fusion.
                val estimate=tracker.update(now,obd,raw,State.networkFix,
                    State.yawDegPerSec)
                val gpsOk=raw!=null && raw.source=="GPS" &&
                    !AirAlerts.autoNavigationAlertActive() &&
                    raw.accuracy<=25f && (State.gpsSpeedKmh?:0.0)<=180.0 &&
                    estimate!=null &&
                    RoutingEngine.meters(
                        RoutingEngine.Point(raw.lat,raw.lon),
                        RoutingEngine.Point(estimate.lat,estimate.lon))<45.0
                visible=if(gpsOk)raw else estimate?:raw
                if(visible!=null){
                    val progress=r.progress(RoutingEngine.Point(visible.lat,visible.lon))
                    val remaining=progress.remainingMeters
                    status.text=(if(gpsOk)"GPS" else "OBD/DR · расчёт")+
                        " · "+String.format(Locale.getDefault(),"%.1f км",remaining/1000.0)
                    directions.text=progress.nextTurn?.let {
                        "Через "+progress.nextTurnMeters.roundToInt()+" м: "+it.instruction
                    }?:"Продолжай движение по маршруту"
                }
            }
            if(visible!=null){
                val p=LatLng(visible.lat,visible.lon)
                if(car==null)car=map?.addMarker(MarkerOptions().position(p)
                    .title("Автомобиль · оценочное положение"))
                else car?.position=p
                if(active)map?.animateCamera(CameraUpdateFactory.newLatLng(p))
            }else if(active){
                status.text="Нет координат и OBD · позиция не обновляется"
            }
            lastRaw=raw
            handler.postDelayed(this,1000L)
        }
    }
    override fun onResume(){super.onResume();mapView.onResume();handler.post(refresh)}
    override fun onPause(){handler.removeCallbacks(refresh);mapView.onPause();super.onPause()}
    override fun onStart(){super.onStart();mapView.onStart()}
    override fun onStop(){mapView.onStop();super.onStop()}
    override fun onLowMemory(){super.onLowMemory();mapView.onLowMemory()}
    override fun onSaveInstanceState(outState:Bundle){
        super.onSaveInstanceState(outState);mapView.onSaveInstanceState(outState)
    }
    override fun onDestroy(){
        handler.removeCallbacks(refresh)
        if(active)stopGuidance()
        mapView.onDestroy()
        super.onDestroy()
    }
    private fun routeBearing(a:RoutingEngine.Point,b:RoutingEngine.Point):Double {
        return (Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),
            b.lat-a.lat))+360.0)%360.0
    }
}

/** Pure display/route odometer. Never feeds its estimate into GPS authenticity checks. */
internal class RouteOdometer {
    private var route:RoutingEngine.Route?=null
    private var lengths=DoubleArray(0)
    private var distance=0.0
    private var lastTime=0L
    private var lastFixTime=0L
    private var yaw=0.0
    fun reset(){route=null;lengths=DoubleArray(0);distance=0.0;lastTime=0L;lastFixTime=0L;yaw=0.0}
    fun bind(r:RoutingEngine.Route,initial:State.NavFix?){
        reset();route=r
        lengths=DoubleArray(r.points.size)
        for(i in 1 until r.points.size)lengths[i]=lengths[i-1]+
            RoutingEngine.meters(r.points[i-1],r.points[i])
        distance=initial?.let{project(it.lat,it.lon)?.first}?:0.0
        lastTime=SystemClock.elapsedRealtime()
    }
    fun update(now:Long,obd:Double?,fix:State.NavFix?,
               network:State.NavFix?,yawDegreesPerSecond:Double):State.NavFix? {
        val r=route?:return null
        if(lengths.size<2)return null
        val dt=((now-lastTime)/1000.0).coerceIn(0.0,2.0)
        lastTime=now
        if(obd!=null)distance=(distance+obd/3.6*dt).coerceAtMost(lengths.last())
        if(obd!=null && obd>5 && yawDegreesPerSecond.isFinite())
            yaw=(yaw+yawDegreesPerSecond*dt).coerceIn(-140.0,140.0)
        else yaw*=0.7
        // Turn only to a nearby maneuver, with a strict bounded adjustment.
        if(abs(yaw)>30 && obd!=null) {
            val step=r.maneuvers.firstOrNull{
                val idx=it.shapeIndex.coerceIn(1,r.points.lastIndex-1)
                val before=bearing(r.points[idx-1],r.points[idx])
                val after=bearing(r.points[idx],r.points[idx+1])
                val delta=((after-before+540.0)%360.0)-180.0
                abs(delta)>25 && sign(delta)==sign(yaw) &&
                    lengths[idx] in (distance-20.0)..(distance+45.0)
            }
            if(step!=null){
                distance=max(distance,min(distance+12.0,lengths[step.shapeIndex]))
                yaw=0.0
            }
        }
        for(candidate in listOfNotNull(fix,network)){
            if(candidate.elapsedMs<=lastFixTime || candidate.accuracy>150f)continue
            if(candidate.source.contains("Ручн"))continue
            val matched=project(candidate.lat,candidate.lon)?:continue
            val along=matched.first
            val lateral=matched.second
            if(lateral>max(70.0,candidate.accuracy*1.8))continue
            if(abs(along-distance)>max(90.0,candidate.accuracy*2.5))continue
            val confidence=if(candidate.source=="GPS" && candidate.accuracy<30f)0.12 else 0.04
            distance=(distance+(along-distance).coerceIn(-20.0,20.0)*confidence)
                .coerceIn(0.0,lengths.last())
            lastFixTime=candidate.elapsedMs
        }
        val d=distance.coerceIn(0.0,lengths.last())
        var i=0
        while(i<lengths.lastIndex-1 && lengths[i+1]<d)i++
        val segment=(lengths[i+1]-lengths[i]).coerceAtLeast(0.001)
        val fraction=((d-lengths[i])/segment).coerceIn(0.0,1.0)
        val a=r.points[i];val b=r.points[i+1]
        return State.NavFix(a.lat+(b.lat-a.lat)*fraction,
            a.lon+(b.lon-a.lon)*fraction,bearing(a,b),100f,
            "OBD · расчёт по маршруту",now)
    }
    private fun project(lat:Double,lon:Double):Pair<Double,Double>? {
        val r=route?:return null
        var min=Double.POSITIVE_INFINITY;var along=0.0
        for(i in 0 until r.points.lastIndex){
            // Avoid snapping across a later loop of the same route.
            if(lengths[i+1]<distance-300.0 || lengths[i]>distance+300.0)continue
            val a=r.points[i];val b=r.points[i+1]
            val scale=111195.0;val cosLat=cos(Math.toRadians(lat))
            val ax=(a.lon-lon)*cosLat*scale;val ay=(a.lat-lat)*scale
            val bx=(b.lon-lon)*cosLat*scale;val by=(b.lat-lat)*scale
            val vx=bx-ax;val vy=by-ay;val len=vx*vx+vy*vy
            val t=if(len<0.1)0.0 else (-(ax*vx+ay*vy)/len).coerceIn(0.0,1.0)
            val lateral=hypot(ax+vx*t,ay+vy*t)
            if(lateral<min){min=lateral;along=lengths[i]+(lengths[i+1]-lengths[i])*t}
        }
        return if(min.isFinite())along to min else null
    }
    private fun bearing(a:RoutingEngine.Point,b:RoutingEngine.Point):Double =
        (Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),
            b.lat-a.lat))+360.0)%360.0
}
