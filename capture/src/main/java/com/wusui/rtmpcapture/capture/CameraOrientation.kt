package com.wusui.rtmpcapture.capture

import kotlin.math.abs
import kotlin.math.min

/** Physical device degrees are clockwise; Display rotation constants use the opposite sense. */
object CameraOrientation {
    fun fromDisplayRotation(quarterTurns: Int): Int {
        require(quarterTurns in 0..3)
        return ((4-quarterTurns)%4)*90
    }

    /** Retain orientation when flat/unknown, and use 5° hysteresis around quadrant boundaries. */
    fun update(rawDegrees: Int, previousDegrees: Int): Int {
        require(previousDegrees in listOf(0,90,180,270))
        if(rawDegrees !in 0..359) return previousDegrees
        val distance=abs(rawDegrees-previousDegrees)
        if(min(distance,360-distance)<50) return previousDegrees
        return ((rawDegrees+45)/90*90)%360
    }

    /** SurfaceTexture already includes camera sensor rotation, crop and native mirroring. */
    fun producerSwapsAxes(matrix: FloatArray): Boolean {
        require(matrix.size>=16)
        return abs(matrix[1])>abs(matrix[0])
    }

    /** UV transform applied BEFORE the producer matrix; add device rotation only, never sensor rotation. */
    fun writeUvMatrix(out: FloatArray, sourceWidth: Int, sourceHeight: Int,
                      outputWidth: Int, outputHeight: Int, producerSwapsAxes: Boolean,
                      deviceDegrees: Int) {
        require(out.size>=16 && sourceWidth>0 && sourceHeight>0 && outputWidth>0 && outputHeight>0)
        require(deviceDegrees in listOf(0,90,180,270))
        val swapped=producerSwapsAxes xor (deviceDegrees%180!=0)
        val sourceAspect=if(swapped) sourceHeight.toFloat()/sourceWidth else sourceWidth.toFloat()/sourceHeight
        val outputAspect=outputWidth.toFloat()/outputHeight
        val sx=if(sourceAspect>outputAspect) outputAspect/sourceAspect else 1f
        val sy=if(sourceAspect<outputAspect) sourceAspect/outputAspect else 1f
        val cos=when(deviceDegrees) { 0->1f; 180->-1f; else->0f }
        val sin=when(deviceDegrees) { 90->1f; 270->-1f; else->0f }
        out.fill(0f)
        out[0]=cos*sx; out[1]=sin*sx
        out[4]=-sin*sy; out[5]=cos*sy
        out[10]=1f; out[15]=1f
        out[12]=.5f-.5f*(out[0]+out[4]); out[13]=.5f-.5f*(out[1]+out[5])
    }
}
