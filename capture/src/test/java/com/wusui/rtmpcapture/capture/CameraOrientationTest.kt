package com.wusui.rtmpcapture.capture

import org.junit.Assert.*
import org.junit.Test

class CameraOrientationTest {
    private val camera90=floatArrayOf(0f,-1f,0f,0f,-1f,0f,0f,0f,0f,0f,1f,0f,1f,1f,0f,1f)
    @Test fun displayRotationUsesInversePhysicalDirection() {
        assertEquals(listOf(0,270,180,90),(0..3).map(CameraOrientation::fromDisplayRotation))
    }
    @Test fun unknownFlatAndBoundaryJitterKeepLastDirection() {
        assertEquals(90,CameraOrientation.update(-1,90))
        assertEquals(0,CameraOrientation.update(49,0))
        assertEquals(90,CameraOrientation.update(50,0))
        assertEquals(90,CameraOrientation.update(41,90))
        assertEquals(0,CameraOrientation.update(40,90))
        assertEquals(0,CameraOrientation.update(359,0))
        assertEquals(270,CameraOrientation.update(310,0))
        assertEquals(180,CameraOrientation.update(180,0))
    }
    @Test fun nativeSensorTransformIsNotAppliedTwice() {
        assertTrue(CameraOrientation.producerSwapsAxes(camera90))
        val uv=FloatArray(16)
        CameraOrientation.writeUvMatrix(uv,1280,720,1280,720,true,0)
        // Portrait producer already rotated; add a centered crop, with no second 90° turn.
        assertEquals(1f,uv[0],.00001f)
        assertEquals(0f,uv[1],.00001f)
        assertEquals(0f,uv[4],.00001f)
        assertEquals(.31640625f,uv[5],.00001f)
        assertEquals(.5f-.5f*uv[5],uv[13],.00001f)
    }
    @Test fun landscapeAddsOnlyDeviceTurnAndRestoresFullAspect() {
        val uv=FloatArray(16)
        CameraOrientation.writeUvMatrix(uv,1280,720,1280,720,true,270)
        assertEquals(0f,uv[0],.00001f); assertEquals(-1f,uv[1],.00001f)
        assertEquals(1f,uv[4],.00001f); assertEquals(0f,uv[5],.00001f)
        // Native 90° matrix followed by the device's 270° turn equals a plain Y flip.
        fun combined(row:Int,col:Int)=(0..3).sumOf { k -> (camera90[k*4+row]*uv[col*4+k]).toDouble() }.toFloat()
        assertEquals(1f,combined(0,0),.00001f)
        assertEquals(-1f,combined(1,1),.00001f)
        assertEquals(0f,combined(0,1),.00001f)
        assertEquals(0f,combined(1,0),.00001f)
    }
    @Test fun allDirectionsKeepTheImageCenterAndProportions() {
        for(swapped in listOf(false,true)) for(degrees in listOf(0,90,180,270)) {
            val uv=FloatArray(16)
            CameraOrientation.writeUvMatrix(uv,1280,720,1280,720,swapped,degrees)
            assertEquals(.5f,.5f*uv[0]+.5f*uv[4]+uv[12],.00001f)
            assertEquals(.5f,.5f*uv[1]+.5f*uv[5]+uv[13],.00001f)
            for(x in listOf(0f,1f)) for(y in listOf(0f,1f)) {
                assertTrue(uv[0]*x+uv[4]*y+uv[12] in 0f..1f)
                assertTrue(uv[1]*x+uv[5]*y+uv[13] in 0f..1f)
            }
        }
    }
}
