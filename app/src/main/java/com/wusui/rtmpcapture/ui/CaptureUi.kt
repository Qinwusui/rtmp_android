package com.wusui.rtmpcapture.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wusui.rtmpcapture.capture.*
import com.wusui.rtmpcapture.data.*
import com.wusui.rtmpcapture.service.CaptureService
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val pages=listOf("采集","服务器","画质","补光布局","记录","监看")

@Composable
fun CaptureUi(vm:MainViewModel,binder:CaptureService.LocalBinder?,start:()->Unit,screen:()->Unit,secure:(Boolean)->Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val server=servers.firstOrNull { it.id==selected }?:servers.firstOrNull()
    SideEffect { secure(page==1 || page==5) }
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF146C56),secondary=Color(0xFF42685B))) {
        Scaffold(bottomBar={
            NavigationBar {
                pages.forEachIndexed { index,label -> NavigationBarItem(selected=page==index,onClick={ page=index },icon={ Text((index+1).toString()) },label={ Text(label,maxLines=1) }) }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("屏幕摄像合流",style=MaterialTheme.typography.headlineSmall)
                if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                when(page) {
                    0 -> CapturePage(stats,server,binder,start,screen,vm::stop,vm::removeScreen,{ server?.let(vm::queryStatus) })
                    1 -> ServerPage(servers,selected,vm)
                    2 -> QualityPage(settings,vm)
                    3 -> LightLayoutPage(settings,vm.flash,vm::saveSettings)
                    4 -> HistoryPage(vm)
                    5 -> MonitorPage(server)
                }
            }
        }
    }
}

@Composable private fun CapturePage(stats:CaptureStats,server:ServerConfig?,binder:CaptureService.LocalBinder?,start:()->Unit,screen:()->Unit,stop:()->Unit,remove:()->Unit,status:()->Unit) {
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(server?.name?:"尚未配置服务器",style=MaterialTheme.typography.titleMedium)
            Text(stats.message)
            Text(String.format(Locale.CHINA,"实际 %.1f fps · %.2f Mbps · 编码目标 %.2f Mbps",stats.fps,stats.bitrate/1_000_000.0,stats.targetBitrate/1_000_000.0))
            Text("丢帧 ${stats.dropped} · 队列 ${stats.queue}/128 · 重连 ${stats.reconnects}")
            Text("运行 ${stats.elapsedSeconds} 秒 · 屏幕：${if(stats.screen) "已接入" else "未采集"}")
        } } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=start,enabled=!stats.running && server!=null) { Text("启动采集") }
            OutlinedButton(onClick=stop,enabled=stats.running) { Text("停止采集") }
        } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=screen,enabled=stats.running && !stats.screen) { Text("恢复屏幕合流") }
            OutlinedButton(onClick=remove,enabled=stats.screen) { Text("移除屏幕") }
        } }
        item { OutlinedButton(onClick=status,enabled=server?.statusUrl?.isNotBlank()==true) { Text("查询 LAL 状态") } }
        item { Text("摄像头为主画面；熄屏后保留摄像头和麦克风。恢复屏幕时每次重新系统授权。",style=MaterialTheme.typography.bodySmall) }
        if(stats.running && !stats.screen && binder!=null) item { Preview(binder) }
        if(stats.screen) item { Text("屏幕采集中，本地合成预览已关闭。请切换到需要共享的应用。") }
    }
}

@Composable private fun Preview(binder:CaptureService.LocalBinder) {
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    DisposableEffect(binder) { onDispose { binder.preview(null); previewSurface?.release(); previewSurface=null } }
    AndroidView(modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f),factory={ context ->
        TextureView(context).apply { surfaceTextureListener=object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture:SurfaceTexture,width:Int,height:Int) { previewSurface=Surface(texture); binder.preview(previewSurface,width,height) }
            override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,width:Int,height:Int) { binder.preview(previewSurface,width,height) }
            override fun onSurfaceTextureDestroyed(texture:SurfaceTexture):Boolean { binder.preview(null); previewSurface?.release(); previewSurface=null; return true }
            override fun onSurfaceTextureUpdated(texture:SurfaceTexture)=Unit
        } }
    })
}

@Composable private fun ServerPage(servers:List<ServerConfig>,selected:Long,vm:MainViewModel) {
    var editing by remember { mutableStateOf<ServerConfig?>(null) }
    var form by remember { mutableStateOf(false) }
    if(form) {
        ServerForm(editing,vm) { form=false; editing=null }
    } else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Button(onClick={ editing=null; form=true }) { Text("添加服务器") } }
        items(servers,key={ it.id }) { server -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(server.name,style=MaterialTheme.typography.titleMedium)
            Text("流名：${server.stream}")
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={ vm.selected.value=server.id }) { Text(if(selected==server.id || selected==0L && servers.firstOrNull()?.id==server.id) "已选择" else "选择") }
                TextButton(onClick={ editing=server; form=true }) { Text("编辑") }
                TextButton(onClick={ vm.testAuth(server) }) { Text("验证认证") }
                TextButton(onClick={ vm.deleteServer(server.id) }) { Text("删除") }
            }
        } } }
    }
}

@Composable private fun ServerForm(server:ServerConfig?,vm:MainViewModel,done:()->Unit) {
    var name by remember(server) { mutableStateOf(server?.name?:"本地 LAL") }
    var url by remember(server) { mutableStateOf(server?.rtmpUrl?:"rtmp://192.168.1.2:1935/live/demo") }
    var stream by remember(server) { mutableStateOf(server?.stream?:"demo") }
    var auth by remember(server) { mutableStateOf(server?.authUrl?:"http://192.168.1.2:8081/v1/publish-token") }
    var user by remember(server) { mutableStateOf(server?.username?:"") }
    var password by remember(server) { mutableStateOf("") }
    var monitor by remember(server) { mutableStateOf(server?.monitorUrl?:"") }
    var status by remember(server) { mutableStateOf(server?.statusUrl?:"") }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Field("名称",name,{ name=it }) }; item { Field("RTMP 地址",url,{ url=it }) }
        item { Field("流名",stream,{ stream=it }) }; item { Field("认证接口完整地址",auth,{ auth=it }) }
        item { Field("用户名",user,{ user=it }) }
        item { OutlinedTextField(password,{ password=it },label={ Text(if(server==null) "密码" else "密码（留空保持原密码）") },visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),singleLine=true) }
        item { Field("HTTP-FLV 监看地址（可选）",monitor,{ monitor=it }) }; item { Field("LAL 状态接口（可选）",status,{ status=it }) }
        item { Text("凭据由 Android Keystore 加密保存；HTTP 仅适用于可信局域网，认证服务支持 HTTPS 反向代理。",style=MaterialTheme.typography.bodySmall) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick={ vm.saveServer(server?.id?:0,name,url,stream,auth,user,password,monitor,status) { password=""; done() } }) { Text("保存服务器") }
            OutlinedButton(onClick={ password=""; done() }) { Text("取消") }
        } }
    }
}

@Composable private fun QualityPage(s:CaptureSettings,vm:MainViewModel) {
    var width by remember(s) { mutableStateOf(s.width.toString()) }; var height by remember(s) { mutableStateOf(s.height.toString()) }
    var fps by remember(s) { mutableStateOf(s.fps.toString()) }; var rate by remember(s) { mutableStateOf((s.bitrate/1000).toString()) }
    var minimum by remember(s) { mutableStateOf((s.minBitrate/1000).toString()) }; var adaptive by remember(s) { mutableStateOf(s.adaptive) }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Text("默认 1280×720 · 30fps · 3Mbps；画质变更在下次启动生效。") }
        item { Field("宽度",width,{ width=it }) }; item { Field("高度",height,{ height=it }) }
        item { Field("帧率 fps",fps,{ fps=it }) }; item { Field("目标码率 kbps",rate,{ rate=it }) }; item { Field("最低自适应码率 kbps",minimum,{ minimum=it }) }
        item { Row { Checkbox(adaptive,{ adaptive=it }); Text("拥塞时自适应码率") } }
        item { Button(onClick={
            val value=s.copy(width=width.toIntOrNull()?:0,height=height.toIntOrNull()?:0,fps=fps.toIntOrNull()?:0,bitrate=(rate.toLongOrNull()?.times(1000)?.takeIf { it<=Int.MAX_VALUE }?:0).toInt(),minBitrate=(minimum.toLongOrNull()?.times(1000)?.takeIf { it<=Int.MAX_VALUE }?:0).toInt(),adaptive=adaptive)
            vm.saveSettings(value)
        }) { Text("保存画质") } }
        item { OutlinedButton(onClick=vm::validateHardware) { Text("检查当前保存配置的硬件能力") } }
    }
}

@Composable private fun LightLayoutPage(s:CaptureSettings,flash:Boolean,save:(CaptureSettings)->Unit) {
    var mode by remember(s) { mutableStateOf(s.light) }; var corner by remember(s) { mutableStateOf(s.corner) }
    var start by remember(s) { mutableStateOf(timeText(s.nightStart)) }; var end by remember(s) { mutableStateOf(timeText(s.nightEnd)) }
    var width by remember(s) { mutableFloatStateOf(s.pipWidth) }; var height by remember(s) { mutableFloatStateOf(s.pipMaxHeight) }
    var error by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Text(if(flash) "夜间补光（设备本地时间）" else "当前摄像头无闪光灯，补光已禁用") }
        item { Row { LightMode.entries.forEach { value -> FilterChip(selected=mode==value,onClick={ mode=value },enabled=flash,label={ Text(when(value) { LightMode.AUTO->"自动"; LightMode.ON->"常开"; LightMode.OFF->"关闭" }) }) } } }
        item { Field("开始 HH:mm",start,{ start=it },flash) }; item { Field("结束 HH:mm",end,{ end=it },flash) }
        item { Text("小窗位置") }
        item { Row { Corner.entries.forEachIndexed { index,value -> FilterChip(selected=corner==value,onClick={ corner=value },label={ Text(listOf("左上","右上","左下","右下")[index]) }) } } }
        item { Text("宽度 ${ (width*100).toInt() }%"); Slider(width,{ width=it },valueRange=.1f.. .6f) }
        item { Text("高度上限 ${ (height*100).toInt() }%"); Slider(height,{ height=it },valueRange=.1f.. .4f) }
        item { if(error.isNotEmpty()) Text(error); Button(onClick={
            val a=parseTime(start); val b=parseTime(end)
            if(a==null || b==null) error="请输入有效的 HH:mm 时间" else { error=""; save(s.copy(light=if(flash) mode else LightMode.OFF,nightStart=a,nightEnd=b,corner=corner,pipWidth=width,pipMaxHeight=height)) }
        }) { Text("保存补光与布局") } }
    }
}
private fun timeText(minute:Int)=String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60)
private fun parseTime(value:String):Int?=runCatching { LocalTime.parse(value,DateTimeFormatter.ofPattern("HH:mm")).let { it.hour*60+it.minute } }.getOrNull()
@Composable private fun Field(label:String,value:String,change:(String)->Unit,enabled:Boolean=true) { OutlinedTextField(value,change,label={ Text(label) },singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth()) }

@Composable private fun HistoryPage(vm:MainViewModel) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SessionSummary?>(null) }
    var events by remember { mutableStateOf(emptyList<SessionEvent>()) }
    LaunchedEffect(selected) { events=selected?.let { vm.events(it.id) }?:emptyList() }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(sessions.isEmpty()) item { Text("暂无采集会话。仅保存摘要与关键事件，不保存音视频。") }
        items(sessions,key={ it.id }) { session -> Card(modifier=Modifier.fillMaxWidth(),onClick={ selected=if(selected==session) null else session }) { Column(Modifier.padding(12.dp)) {
            Text(session.serverName)
            Text(Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")))
            Text("${session.durationSeconds} 秒 · 重连 ${session.reconnects} · 丢帧 ${session.dropped}")
            Text(session.endReason)
        } } }
        items(events,key={ "event-${it.id}" }) { Text(it.message,style=MaterialTheme.typography.bodySmall) }
    }
}
