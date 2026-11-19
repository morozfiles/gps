package ua.obdgps.nav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * Tiny on-device neural regression network for calibration of INS/OBD drift.
 * 4 inputs -> 8 tanh hidden neurons -> 2 tanh outputs (distance/yaw gains).
 *
 * Learns ONLY while the independent GPS-vs-DR comparison is running.
 * GPS data must already have passed NavService spoof/jump checks, and each
 * training pair is additionally screened for age, precision, movement and
 * agreement with OBD. On FYT, the external GM906BT is another app's mock
 * provider; only accepted, explicitly marked external fixes may train.
 * The network never receives raw GPS during navigation.
 *
 * IMPORTANT: this calibrates two systematic gains. It is not a GNSS
 * replacement, and cannot detect a sophisticated consistent GPS spoof.
 */
internal class AdaptiveDrLearning(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences(
        "adaptive_dr_learning_v1",Context.MODE_PRIVATE)
    private val inputs=4
    private val hidden=8
    private val inputWeights=Array(hidden){h->
        DoubleArray(inputs){i->
            // Deterministic small features, independent of network access.
            0.12*sin((h+1.0)*(i+2.0)*1.7)
        }
    }
    private val hiddenBias=DoubleArray(hidden)
    private val outputWeights=Array(2){DoubleArray(hidden)}
    private val outputBias=DoubleArray(2)
    private var speedExamples=0
    private var yawExamples=0
    private val samples=Array(3){IntArray(3)}
    private val positionError=Array(3){DoubleArray(3)}
    private val distanceScaleSum=Array(3){DoubleArray(3)}
    private val yawScaleSum=Array(3){DoubleArray(3)}
    private var lastPair:PairSample?=null
    private var lastSaveCount=0

    private data class PairSample(
        val gps:Location,
        val dr:DeadReckoner.Pose,
        val timestamp:Long,
        val networkCorrections:Int
    )

    init { load() }

    /** Do not connect examples across a spoof/quarantine or test restart. */
    fun resetSegment(){lastPair=null}

    /** Explainability: how many straight/left/right examples were learned. */
    fun status():String{
        val count=IntArray(3){category->samples[category].sum()}
        val total=count.sum()
        val readiness=if(speedExamples>=20)
            "дистанция готова" else "дистанция: $speedExamples/20"
        val turns=if(yawExamples>=10)"повороты готовы"
            else "повороты: $yawExamples/10"
        return "ИИ: $total участков · прямо ${count[0]}, влево ${count[1]}, "+
            "вправо ${count[2]} · $readiness · $turns"
    }

    fun detail():String {
        val names=arrayOf("Прямо","Левые повороты","Правые повороты")
        val bands=arrayOf("до 35","35–70","более 70")
        val report=ArrayList<String>()
        for(dir in 0..2){
            val n=samples[dir].sum()
            val err=if(n>0)positionError[dir].sum()/n else 0.0
            report.add("${names[dir]}: $n участков · среднее "+
                "расхождение GPS/DR ${err.roundToInt()} м")
            for(band in 0..2){
                val k=samples[dir][band]
                if(k==0)continue
                val factor=distanceScaleSum[dir][band]/k
                report.add("  ${bands[band]} км/ч: $k · длина "+
                    "${"%.3f".format(java.util.Locale.US,factor)}×")
            }
        }
        if(speedExamples>=20) {
            val straight=speedGain(45.0,0.0)
            report.add("Нейросеть, прямая 45 км/ч: "+
                "${"%.3f".format(java.util.Locale.US,straight)}× дистанция")
        }
        if(yawExamples>=10){
            val turn=yawGain(45.0,5.0)
            report.add("Нейросеть, поворот 45 км/ч: "+
                "${"%.3f".format(java.util.Locale.US,turn)}× угол")
        }
        return report.joinToString("\n")
    }

    fun speedGain(speedKmh:Double,yawDegPerSec:Double):Double {
        if(speedExamples<20 || !speedKmh.isFinite())return 1.0
        val adjustment=predict(features(speedKmh,yawDegPerSec))[0]
        return (1.0+adjustment).coerceIn(0.94,1.06)
    }

    fun yawGain(speedKmh:Double,yawDegPerSec:Double):Double {
        if(yawExamples<10 || !speedKmh.isFinite() ||
            !yawDegPerSec.isFinite())return 1.0
        val adjustment=predict(features(speedKmh,yawDegPerSec))[1]
        return (1.0+adjustment).coerceIn(0.90,1.10)
    }

    /** A single accepted, genuinely fresh GPS fix and independent DR pose. */
    fun observeTrustedGps(
        gps:Location, dr:DeadReckoner.Pose?,
        obdKmh:Double?, networkCorrections:Int,
        gpsSampleCount:Int, now:Long=SystemClock.elapsedRealtime(),
        trustedExternalMock:Boolean=false
    ){
        if(dr==null || !gps.hasAccuracy() || gps.accuracy>12f ||
            gps.accuracy<=0f || gpsSampleCount<3 ||
            !gps.hasSpeed() || gps.speed*3.6f !in 12f..130f ||
            obdKmh==null || obdKmh !in 12.0..130.0 ||
            abs(gps.speed*3.6-obdKmh)>max(9.0,obdKmh*0.23) ||
            // GM906BT's companion publishes Android mock GPS. Allow it
            // ONLY after NavService's spoof/route/jump/OBD checks accepted
            // this fix and explicitly identified the external input.
            (gps.isMock && !trustedExternalMock) ||
            GpsSpoofGuard.isLima(gps.latitude,gps.longitude)){
            lastPair=null
            return
        }
        val measurement=Location("model-measurement").apply {
            latitude=dr.lat
            longitude=dr.lon
        }
        // Do not learn after DR has diverged too far from trusted GNSS;
        // the distance could be from a false course or network adjustment.
        if(measurement.distanceTo(gps)>65f){
            lastPair=null
            return
        }
        val prior=lastPair
        if(prior==null){
            lastPair=PairSample(Location(gps),dr,now,networkCorrections)
            return
        }
        val elapsed=(now-prior.timestamp)/1000.0
        if(elapsed<4.0)return
        lastPair=PairSample(Location(gps),dr,now,networkCorrections)
        if(elapsed>14.0 || prior.networkCorrections!=networkCorrections)
            return
        val gpsDistance=prior.gps.distanceTo(gps).toDouble()
        val from=Location("independent-dr").apply {
            latitude=prior.dr.lat; longitude=prior.dr.lon
        }
        val to=Location("independent-dr").apply {
            latitude=dr.lat; longitude=dr.lon
        }
        val drDistance=from.distanceTo(to).toDouble()
        if(gpsDistance<18.0 || drDistance<18.0 ||
            gpsDistance/elapsed*3.6 !in 12.0..130.0)return
        val ratio=gpsDistance/drDistance
        // Reject apparent gains produced by GNSS jitter, a teleport or
        // an already incorrect DR heading. No outlier may train the model.
        if(ratio !in 0.88..1.12)return

        val gpsTurn=if(gps.hasBearing() && prior.gps.hasBearing() &&
            (!gps.hasBearingAccuracy() || gps.bearingAccuracyDegrees<18f) &&
            (!prior.gps.hasBearingAccuracy() ||
                prior.gps.bearingAccuracyDegrees<18f))
            delta(gps.bearing.toDouble(),prior.gps.bearing.toDouble())
            else Double.NaN
        val drTurn=delta(dr.bearing,prior.dr.bearing)
        val turn= gpsTurn.isFinite() && abs(gpsTurn)>=11.0 &&
            abs(drTurn)>=7.0 && gpsTurn*drTurn>0 &&
            abs(gpsTurn-drTurn)<=28.0
        val straight=gpsTurn.isFinite() &&
            abs(gpsTurn)<=7.0 && abs(drTurn)<=10.0
        if(!turn && !straight)return
        val direction=if(straight)0 else if(gpsTurn<0)1 else 2
        val segmentSpeed=gpsDistance/elapsed*3.6
        val band=when{
            segmentSpeed<35.0->0
            segmentSpeed<70.0->1
            else->2
        }
        val error=measurement.distanceTo(gps).toDouble()
        samples[direction][band]++
        positionError[direction][band]+=error
        distanceScaleSum[direction][band]+=ratio
        val avgYaw=if(straight)0.0 else drTurn/elapsed
        val x=features(segmentSpeed,avgYaw)
        // Learn distance gain from both straight stretches and turns.
        train(x,0,ratio-1.0)
        speedExamples++
        if(turn){
            val turnRatio=abs(gpsTurn/drTurn)
            if(turnRatio in 0.82..1.18){
                train(x,1,turnRatio-1.0)
                yawExamples++
                yawScaleSum[direction][band]+=turnRatio
            }
        }
        if(speedExamples-lastSaveCount>=3){
            save()
            lastSaveCount=speedExamples
        }
    }

    fun save(){
        val root=JSONObject()
            .put("version",1)
            .put("speed",speedExamples)
            .put("yaw",yawExamples)
            .put("w1",matrixToJson(inputWeights))
            .put("b1",vectorToJson(hiddenBias))
            .put("w2",matrixToJson(outputWeights))
            .put("b2",vectorToJson(outputBias))
            .put("count",intMatrixToJson(samples))
            .put("err",matrixToJson(positionError))
            .put("distance",matrixToJson(distanceScaleSum))
            .put("yawGain",matrixToJson(yawScaleSum))
        prefs.edit().putString("model",root.toString()).apply()
    }

    private fun features(speed:Double,yaw:Double)=doubleArrayOf(
        (speed/120.0).coerceIn(0.0,1.2),
        (yaw/16.0).coerceIn(-1.0,1.0),
        (abs(yaw)/16.0).coerceIn(0.0,1.0),
        1.0
    )

    private fun predict(x:DoubleArray):DoubleArray{
        val h=DoubleArray(hidden){i->
            tanh(hiddenBias[i]+(0 until inputs).sumOf{j->
                inputWeights[i][j]*x[j]
            })
        }
        return DoubleArray(2){k->
            tanh(outputBias[k]+(0 until hidden).sumOf{i->
                outputWeights[k][i]*h[i]
            })*0.12
        }
    }

    private fun train(x:DoubleArray,head:Int,target:Double){
        val bounded=target.coerceIn(-0.11,0.11)
        // SGD back-propagation with bounded updates. Small embedded model,
        // no cloud, no heavyweight runtime and no transfer of location data.
        repeat(3){
            val h=DoubleArray(hidden){i->
                tanh(hiddenBias[i]+(0 until inputs).sumOf{j->
                    inputWeights[i][j]*x[j]
                })
            }
            val z=outputBias[head]+(0 until hidden).sumOf{i->
                outputWeights[head][i]*h[i]
            }
            val out=tanh(z)*0.12
            val gradient=(out-bounded)*0.12*(1.0-tanh(z).pow(2))
            val prior=outputWeights[head].clone()
            val lr=0.38
            for(i in 0 until hidden){
                outputWeights[head][i]=
                    (outputWeights[head][i]-lr*gradient*h[i])
                        .coerceIn(-1.5,1.5)
            }
            outputBias[head]=(outputBias[head]-lr*gradient)
                .coerceIn(-1.0,1.0)
            for(i in 0 until hidden){
                val back=gradient*prior[i]*(1.0-h[i]*h[i])
                for(j in 0 until inputs)
                    inputWeights[i][j]=(inputWeights[i][j]-
                        lr*back*x[j]).coerceIn(-1.5,1.5)
                hiddenBias[i]=(hiddenBias[i]-lr*back).coerceIn(-1.0,1.0)
            }
        }
    }

    private fun delta(a:Double,b:Double)=((a-b+540.0)%360.0)-180.0

    private fun vectorToJson(a:DoubleArray)=JSONArray().apply{
        a.forEach{put(it)}
    }
    private fun matrixToJson(m:Array<DoubleArray>)=JSONArray().apply{
        m.forEach{put(vectorToJson(it))}
    }
    private fun intMatrixToJson(m:Array<IntArray>)=JSONArray().apply{
        m.forEach{row->put(JSONArray().apply{row.forEach{put(it)}})}
    }
    private fun load(){
        val root=runCatching{
            JSONObject(prefs.getString("model",null)?:return)
        }.getOrNull()?:return
        if(root.optInt("version",-1)!=1)return
        // Reject corrupted persisted arrays instead of publishing arbitrary
        // scaling factors. Never trust NaN/infinite/out-of-range weights.
        runCatching{
            val a=root.getJSONArray("w1")
            val b=root.getJSONArray("w2")
            val b1=root.getJSONArray("b1")
            val b2=root.getJSONArray("b2")
            val cnt=root.getJSONArray("count")
            val err=root.getJSONArray("err")
            val dist=root.getJSONArray("distance")
            val yaw=root.getJSONArray("yawGain")
            require(a.length()==hidden && b.length()==2 &&
                b1.length()==hidden && b2.length()==2 &&
                cnt.length()==3 && err.length()==3 &&
                dist.length()==3 && yaw.length()==3)
            fun finite(v:Double):Double{
                require(v.isFinite() && abs(v)<=1_000_000.0)
                return v
            }
            for(i in 0 until hidden){
                val row=a.getJSONArray(i)
                require(row.length()==inputs)
                for(j in 0 until inputs){
                    val v=finite(row.getDouble(j));require(abs(v)<=1.5)
                    inputWeights[i][j]=v
                }
                hiddenBias[i]=finite(b1.getDouble(i))
                require(abs(hiddenBias[i])<=1.0)
            }
            for(i in 0..1){
                val row=b.getJSONArray(i)
                require(row.length()==hidden)
                for(j in 0 until hidden){
                    val v=finite(row.getDouble(j));require(abs(v)<=1.5)
                    outputWeights[i][j]=v
                }
                outputBias[i]=finite(b2.getDouble(i))
                require(abs(outputBias[i])<=1.0)
            }
            for(i in 0..2){
                val c=cnt.getJSONArray(i)
                val e=err.getJSONArray(i)
                val d=dist.getJSONArray(i)
                val y=yaw.getJSONArray(i)
                require(c.length()==3 && e.length()==3 &&
                    d.length()==3 && y.length()==3)
                for(j in 0..2){
                    samples[i][j]=c.getInt(j).coerceIn(0,100000)
                    positionError[i][j]=finite(e.getDouble(j))
                    distanceScaleSum[i][j]=finite(d.getDouble(j))
                    yawScaleSum[i][j]=finite(y.getDouble(j))
                }
            }
            speedExamples=root.getInt("speed").coerceIn(0,100000)
            yawExamples=root.getInt("yaw").coerceIn(0,100000)
            require(speedExamples>=yawExamples)
        }.onFailure{
            // Corrupt model fails closed: disable trained corrections until
            // enough new trusted training examples are collected.
            speedExamples=0
            yawExamples=0
            samples.forEach{it.fill(0)}
        }
    }
}
