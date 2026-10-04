package com.wusui.rtmpcapture.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wusui.rtmpcapture.capture.*
import com.wusui.rtmpcapture.data.*
import com.wusui.rtmpcapture.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject

class CaptureService:Service() {
    private val dao:CaptureDao by inject()
    private val settings:SettingsStore by inject()
    private val auth:AuthClient by inject()
    private val state:CaptureState by inject()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _,_ -> stopWithReason("采集资源发生异常，已停止并释放资源") })
    private val mutex=Mutex()
    @Volatile private var pipeline:CapturePipeline?=null
    private var ticker:Job?=null
    private var settingsJob:Job?=null
    private var cameraRecovery:Job?=null
    private var wake:PowerManager.WakeLock?=null
    private var sessionId=0L
    private var startedAt=0L
    private var startedMonotonic=0L
    private var nextWakeRefresh=0L
    private var lastMessage=""
    private var eventCount=0
    private var stopping=false
    private var receiverRegistered=false
    private var thermal:PowerManager.OnThermalStatusChangedListener?=null
    private val binder=LocalBinder { surface,width,height -> scope.launch { mutex.withLock { runCatching { pipeline?.preview(surface,width,height) } } }; Unit }
    @androidx.compose.runtime.Stable
    class LocalBinder(private val previewCallback:(Surface?,Int,Int)->Unit):Binder() {
        fun preview(surface:Surface?,width:Int=0,height:Int=0) { previewCallback(surface,width,height) }
    }
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            scope.launch { mutex.withLock {
                if(intent.action==Intent.ACTION_SCREEN_OFF) removeScreen()
                pipeline?.refreshLight()
                if(intent.action==Intent.ACTION_CONFIGURATION_CHANGED && Build.VERSION.SDK_INT<34) {
                    val metrics=android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
                    if(metrics.widthPixels>0 && metrics.heightPixels>0) pipeline?.resizeScreen(metrics.widthPixels,metrics.heightPixels)
                }
            } }
        }
    }
    override fun onCreate() {
        super.onCreate()
        if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("capture","采集推流",NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this,receiver,IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered=true
        if(Build.VERSION.SDK_INT>=29) {
            thermal=PowerManager.OnThermalStatusChangedListener { value -> if(value>=PowerManager.THERMAL_STATUS_SEVERE) stopWithReason("设备严重过热，采集已停止") }
            getSystemService(PowerManager::class.java).addThermalStatusListener(requireNotNull(thermal))
        }
    }
    override fun onBind(intent:Intent):IBinder=binder
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            START -> {
                if(state.stats.value.running) return START_NOT_STICKY
                try { foreground(false) } catch(_:Exception) { state.mutable.value=CaptureStats(message="前台服务启动失败，请在应用可见时授权后启动"); stopSelf(); return START_NOT_STICKY }
                val id=intent.getLongExtra("server",0)
                scope.launch { mutex.withLock { if(pipeline==null && !stopping) start(id) } }
            }
            SCREEN -> {
                if(pipeline==null || stopping) return START_NOT_STICKY
                @Suppress("DEPRECATION")
                val consent=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra("consent",Intent::class.java) else intent.getParcelableExtra("consent")
                if(consent!=null) scope.launch { mutex.withLock {
                    try {
                        withContext(Dispatchers.Main.immediate) { foreground(true) }
                        pipeline?.preview(null); pipeline?.attachScreen(consent)
                    } catch(_:Exception) {
                        state.mutable.value=state.stats.value.copy(screen=false,message="屏幕授权无效，请点击恢复屏幕合流重新授权")
                        withContext(Dispatchers.Main.immediate) { foreground(false) }
                    }
                } }
            }
            REMOVE_SCREEN -> scope.launch { mutex.withLock { removeScreen() } }
            STOP -> stopWithReason("用户停止")
        }
        return START_NOT_STICKY
    }
    private suspend fun start(id:Long) {
        try {
            checkPermission(Manifest.permission.CAMERA); checkPermission(Manifest.permission.RECORD_AUDIO)
            if(Build.VERSION.SDK_INT>=37) checkPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
            val server=dao.server(id)?:error("请先保存并选择服务器")
            val config=settings.settings.first(); Hardware.validate(this,config)
            state.mutable.value=CaptureStats(running=true,message="正在认证与准备采集")
            val address=auth.publishAddress(server)
            startedAt=System.currentTimeMillis()
            sessionId=dao.begin(SessionSummary(serverName=server.name,startedAt=startedAt))
            startedMonotonic=SystemClock.elapsedRealtime()
            eventCount=0; lastMessage=""
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"rtmpcapture:capture").apply { setReferenceCounted(false); acquire(600_000L) }
            nextWakeRefresh=SystemClock.elapsedRealtime()+60_000L
            pipeline=CapturePipeline(this,config,{ value -> state.mutable.value=value },{ reason -> stopWithReason(reason) },
                { scope.launch { mutex.withLock { removeScreen() } } },
                { w,h -> scope.launch { mutex.withLock { pipeline?.resizeScreen(w,h) } } },{ recoverCamera() })
            requireNotNull(pipeline).start(address)
            settingsJob=scope.launch { settings.settings.collect { value -> mutex.withLock { pipeline?.updateSettings(value) } } }
            ticker=scope.launch {
                while(isActive) {
                    delay(1000)
                    mutex.withLock {
                        checkPermission(Manifest.permission.CAMERA); checkPermission(Manifest.permission.RECORD_AUDIO)
                        if(Build.VERSION.SDK_INT>=37) checkPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        if(SystemClock.elapsedRealtime()>=nextWakeRefresh) { wake?.acquire(600_000L); nextWakeRefresh=SystemClock.elapsedRealtime()+60_000L }
                        val sample=pipeline?.sample()?:return@withLock
                        if(sample.message!=lastMessage && eventCount<200) {
                            dao.event(SessionEvent(sessionId=sessionId,time=System.currentTimeMillis(),message=sample.message)); eventCount++; lastMessage=sample.message
                        }
                    }
                }
            }.also { job -> job.invokeOnCompletion { cause -> if(cause!=null && cause !is CancellationException) stopWithReason("采集权限已撤销或统计异常") } }
        } catch(e:Exception) {
            val reason=when(e) { is AuthDenied -> e.message.orEmpty(); is IllegalArgumentException -> e.message?:"配置无效"; is IllegalStateException -> e.message?:"启动失败"; else -> "启动失败：请检查认证服务、网络与采集资源" }
            cleanup(reason); withContext(Dispatchers.Main.immediate) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    private fun checkPermission(permission:String) { check(ContextCompat.checkSelfPermission(this,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED) { "采集或局域网权限未授予" } }
    private suspend fun removeScreen() {
        pipeline?.detachScreen()
        state.mutable.value=state.stats.value.copy(screen=false,message="屏幕已停止，摄像头与麦克风继续推流")
        withContext(Dispatchers.Main.immediate) { foreground(false) }
    }
    @Synchronized private fun recoverCamera() {
        if(cameraRecovery?.isActive==true || stopping) return
        cameraRecovery=scope.launch {
            mutex.withLock { pipeline?.markCameraUnavailable() }
            var attempt=0
            while(isActive) {
                delay(RetryPolicy.delayMillis(attempt++,kotlin.random.Random.nextDouble()))
                val recovered=mutex.withLock {
                    if(pipeline==null) return@withLock true
                    runCatching { pipeline?.recoverCamera() }.isSuccess
                }
                if(recovered) break
            }
        }
    }
    private fun stopWithReason(reason:String) {
        scope.launch { mutex.withLock {
            if(stopping) return@withLock
            stopping=true; cleanup(reason); stopping=false
            withContext(Dispatchers.Main.immediate) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        } }
    }
    private suspend fun cleanup(reason:String) {
        ticker?.cancel(); ticker=null; settingsJob?.cancel(); settingsJob=null; cameraRecovery?.cancel(); cameraRecovery=null
        val sample=state.stats.value
        pipeline?.close(); pipeline=null
        wake?.let { if(it.isHeld) it.release() }; wake=null
        if(sessionId!=0L) {
            runCatching { dao.end(sessionId,System.currentTimeMillis(),(SystemClock.elapsedRealtime()-startedMonotonic).coerceAtLeast(0)/1000,sample.reconnects,sample.dropped,reason); dao.trimEvents(); dao.trimSessions() }
            sessionId=0
        }
        state.mutable.value=sample.copy(running=false,connected=false,screen=false,message=reason)
    }
    private fun foreground(screen:Boolean) {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,CaptureService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"capture").setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("屏幕摄像合流运行中").setContentText(if(screen) "摄像头 · 麦克风 · 屏幕" else "摄像头 · 麦克风")
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause,"停止采集",stop).build()
        if(Build.VERSION.SDK_INT>=29) {
            var types=if(Build.VERSION.SDK_INT>=30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            if(screen) types=types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(1001,notification,types)
        } else startForeground(1001,notification)
    }
    override fun onDestroy() {
        if(receiverRegistered) unregisterReceiver(receiver)
        if(Build.VERSION.SDK_INT>=29) thermal?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        // Finish cleanup even if Android destroys the service without a STOP command.
        scope.launch { mutex.withLock { if(pipeline!=null || sessionId!=0L) cleanup("服务已销毁") }; scope.cancel() }
        super.onDestroy()
    }
    companion object {
        const val START="capture.START"; const val STOP="capture.STOP"; const val SCREEN="capture.SCREEN"; const val REMOVE_SCREEN="capture.REMOVE_SCREEN"
    }
}
