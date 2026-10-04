package com.wusui.rtmpcapture.ui

import android.Manifest
import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.wusui.rtmpcapture.service.CaptureService
import org.koin.androidx.viewmodel.ext.android.viewModel
import androidx.compose.runtime.mutableStateOf

class MainActivity:ComponentActivity() {
    private val model:MainViewModel by viewModel()
    private val service=mutableStateOf<CaptureService.LocalBinder?>(null)
    private var bound=false
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) { service.value=binder as CaptureService.LocalBinder }
        override fun onServiceDisconnected(name:ComponentName) { service.value=null }
    }
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val required=requiredPermissions()
        if(required.all { ContextCompat.checkSelfPermission(this,it)==android.content.pm.PackageManager.PERMISSION_GRANTED }) model.start()
        else model.message.value="需要摄像头、麦克风和适用版本的局域网权限才能采集"
    }
    private val projection=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK && result.data!=null) model.screen(requireNotNull(result.data))
        else model.message.value="未授权屏幕采集，摄像头与麦克风保持运行"
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            CaptureUi(model,service.value,{
                val required=requiredPermissions()
                val asks=required.toMutableList().apply { if(Build.VERSION.SDK_INT>=33) add(Manifest.permission.POST_NOTIFICATIONS) }
                if(asks.any { ContextCompat.checkSelfPermission(this,it)!=android.content.pm.PackageManager.PERMISSION_GRANTED }) permissions.launch(asks.toTypedArray()) else model.start()
            },{
                if(model.stats.value.running) {
                    service.value?.preview(null)
                    projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                }
            },{ secure -> if(secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) })
        }
    }
    private fun requiredPermissions()=buildList { add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO); if(Build.VERSION.SDK_INT>=37) add(Manifest.permission.ACCESS_LOCAL_NETWORK) }
    override fun onStart() { super.onStart(); bound=bindService(Intent(this,CaptureService::class.java),connection,Context.BIND_AUTO_CREATE) }
    override fun onStop() {
        service.value?.preview(null)
        if(bound) unbindService(connection)
        bound=false; service.value=null; super.onStop()
    }
}
