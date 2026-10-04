package com.wusui.rtmpcapture.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wusui.rtmpcapture.capture.*
import com.wusui.rtmpcapture.data.*
import com.wusui.rtmpcapture.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.URI

class MainViewModel(private val context:Application,private val dao:CaptureDao,private val store:SettingsStore,private val secrets:SecretStore,private val auth:AuthClient,state:CaptureState):ViewModel() {
    val servers=dao.servers().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val sessions=dao.sessions().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val settings=store.settings.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),CaptureSettings())
    val stats=state.stats
    val selected=MutableStateFlow(0L)
    val message=MutableStateFlow("")
    val flash=Hardware.hasFlash(context)
    private fun work(block:suspend ()->Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch(e:Exception) {
                if(e is CancellationException) throw e
                message.value=when(e) { is AuthDenied -> e.message.orEmpty(); is IllegalArgumentException -> e.message?:"配置无效"; else -> "操作失败，请检查配置、网络或设备能力" }
            }
        }
    }
    fun saveServer(id:Long,name:String,url:String,stream:String,authUrl:String,user:String,password:String,monitor:String,status:String,onSaved:()->Unit) = work {
        require(name.isNotBlank() && user.isNotBlank()) { "名称和用户名不能为空" }
        PublishAddress.validate(url.trim(),stream.trim(),authUrl.trim())?.let { error -> throw IllegalArgumentException(error) }
        listOf(monitor,status).filter { it.isNotBlank() }.forEach {
            val uri=URI(it); require(uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null) { "监看与状态地址须为不含凭据或参数的 HTTP(S) 地址" }
        }
        val encrypted=if(password.isNotEmpty()) secrets.encrypt(password) else dao.server(id)?.encryptedPassword?:throw IllegalArgumentException("首次保存须填写密码")
        val saved=dao.save(ServerConfig(id,name.trim(),url.trim(),stream.trim(),authUrl.trim(),user.trim(),encrypted,monitor.trim(),status.trim()))
        selected.value=if(id!=0L) id else saved
        message.value="服务器已保存"
        withContext(Dispatchers.Main) { onSaved() }
    }
    fun deleteServer(id:Long)=work { require(!stats.value.running) { "请先停止采集再删除配置" }; dao.deleteServer(id); if(selected.value==id) selected.value=0 }
    fun testAuth(server:ServerConfig)=work { auth.publishAddress(server); message.value="认证成功，流发布权限已确认" }
    fun queryStatus(server:ServerConfig)=work { require(server.statusUrl.isNotBlank()) { "请先配置 LAL 状态接口" }; message.value=auth.queryStatus(server.statusUrl) }
    fun saveSettings(value:CaptureSettings)=work { store.save(value); message.value="设置已保存，分辨率、帧率和码率在下次启动生效" }
    fun validateHardware()=work {
        try { Hardware.validate(context,settings.value); message.value="硬件支持当前配置" }
        catch(e:Exception) { message.value=e.message?:"硬件不支持当前配置" }
    }
    fun start() {
        val id=selected.value.takeIf { it!=0L }?:servers.value.firstOrNull()?.id?:run { message.value="请先添加服务器"; return }
        try { ContextCompat.startForegroundService(context,Intent(context,CaptureService::class.java).setAction(CaptureService.START).putExtra("server",id)) }
        catch(_:Exception) { message.value="无法启动前台服务，请保持应用可见并授予权限" }
    }
    fun stop() { context.startService(Intent(context,CaptureService::class.java).setAction(CaptureService.STOP)) }
    fun screen(consent:Intent) { context.startService(Intent(context,CaptureService::class.java).setAction(CaptureService.SCREEN).putExtra("consent",consent)) }
    fun removeScreen() { context.startService(Intent(context,CaptureService::class.java).setAction(CaptureService.REMOVE_SCREEN)) }
    suspend fun events(id:Long)=withContext(Dispatchers.IO) { dao.events(id) }
}
