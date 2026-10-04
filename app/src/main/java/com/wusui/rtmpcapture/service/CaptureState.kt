package com.wusui.rtmpcapture.service

import com.wusui.rtmpcapture.capture.CaptureStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CaptureState {
    internal val mutable=MutableStateFlow(CaptureStats())
    val stats=mutable.asStateFlow()
}
