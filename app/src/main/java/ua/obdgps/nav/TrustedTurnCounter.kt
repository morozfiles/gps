package ua.obdgps.nav

import kotlin.math.abs
import kotlin.math.sign

/**
 * Count actual course changes from a succession of vetted independent
 * location headings. This is OBSERVATION, not gyro-yaw model training.
 *
 * One 90-degree manoeuvre counts once. A stable straight section releases
 * the lock for the next turn. A single heading teleport does not count.
 */
internal class TrustedTurnCounter {
    enum class Direction { LEFT, RIGHT }
    private var previousCourse:Double?=null
    private var previousMs=0L
    private var accumulated=0.0
    private var accumulationStarted=0L
    private var turnLocked=false
    private var steadySinceMs=0L
    private var lastDetectedMs=0L

    fun reset() {
        previousCourse=null
        previousMs=0L
        accumulated=0.0
        accumulationStarted=0L
        turnLocked=false
        steadySinceMs=0L
    }

    fun observe(courseDegrees:Double,nowMs:Long):Direction? {
        if(!courseDegrees.isFinite() || nowMs<=0L)return null
        val course=((courseDegrees%360)+360)%360
        val prior=previousCourse
        val age=nowMs-previousMs
        previousCourse=course
        previousMs=nowMs
        if(prior==null || age !in 200L..8000L){
            accumulated=0.0
            accumulationStarted=0L
            steadySinceMs=0L
            turnLocked=false
            return null
        }
        val step=((course-prior+540)%360)-180
        if(abs(step)>42.0){
            // Isolated provider course jumps are not real vehicle turns.
            accumulated=0.0
            accumulationStarted=0L
            steadySinceMs=0L
            return null
        }
        if(turnLocked) {
            if(abs(step)<2.0) {
                if(steadySinceMs==0L)steadySinceMs=nowMs
                if(nowMs-steadySinceMs>=2400L) {
                    turnLocked=false
                    accumulated=0.0
                    accumulationStarted=0L
                }
            } else steadySinceMs=0L
            return null
        }
        if(abs(step)<1.5){
            if(accumulationStarted>0L && nowMs-accumulationStarted>5000L) {
                accumulated=0.0
                accumulationStarted=0L
            }
            return null
        }
        if(accumulated==0.0 || sign(step)!=sign(accumulated)){
            accumulated=step
            accumulationStarted=nowMs-age
        } else accumulated+=step
        if(abs(accumulated)<26.0 ||
            nowMs-accumulationStarted<700L ||
            nowMs-lastDetectedMs<4000L)return null
        val result=if(accumulated>0)Direction.RIGHT else Direction.LEFT
        lastDetectedMs=nowMs
        turnLocked=true
        steadySinceMs=0L
        accumulated=0.0
        accumulationStarted=0L
        return result
    }
}
