package com.wusui.rtmpcapture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.LifecycleStartEffect
import com.wusui.rtmpcapture.data.ServerConfig

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun MonitorPage(server:ServerConfig?) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    var enabled by remember(server?.id) { mutableStateOf(false) }
    var sound by remember(server?.id) { mutableStateOf(false) }
    Text("服务器 HTTP-FLV 监看默认关闭，开启后会额外占用带宽和解码资源。")
    Button(onClick={ enabled=!enabled },enabled=server?.monitorUrl?.isNotBlank()==true) { Text(if(enabled) "关闭监看" else "开启监看") }
    if(enabled && server!=null) {
        val context=LocalContext.current
        var status by remember { mutableStateOf("正在连接监看") }
        val player=remember(server.monitorUrl) {
            ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory())).build().apply {
                volume=0f; setMediaItem(MediaItem.fromUri(server.monitorUrl)); prepare(); playWhenReady=true
            }
        }
        SideEffect { player.volume=if(sound) 1f else 0f }
        DisposableEffect(player) {
            val listener=object:Player.Listener {
                override fun onVideoSizeChanged(videoSize:VideoSize) { status="解码画面 ${videoSize.width}×${videoSize.height}" }
                override fun onRenderedFirstFrame() { status="服务器画面已解码" }
                override fun onPlayerError(error:PlaybackException) { status="监看失败，请检查 HTTP-FLV 地址与服务器状态" }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener); player.release() }
        }
        LifecycleStartEffect(player) { player.play(); onStopOrDispose { player.pause() } }
        Text(status)
        TextButton(onClick={ sound=!sound }) { Text(if(sound) "静音监看" else "开启监看音频") }
        AndroidView(factory={ PlayerView(it).apply { this.player=player } },modifier=Modifier.fillMaxWidth().height(240.dp))
    }
    }
}
