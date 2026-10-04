package com.wusui.rtmpcapture.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.wusui.rtmpcapture.capture.*
import kotlinx.coroutines.flow.*
import java.io.IOException

private val Context.captureDataStore by preferencesDataStore("capture-settings")
class SettingsStore(context:Context) {
    private val store=context.captureDataStore
    val settings:Flow<CaptureSettings> = store.data.catch { if(it is IOException) emit(emptyPreferences()) else throw it }.map { p ->
        val d=CaptureSettings()
        CaptureSettings(p[intPreferencesKey("width")]?:d.width,p[intPreferencesKey("height")]?:d.height,p[intPreferencesKey("fps")]?:d.fps,p[intPreferencesKey("bitrate")]?:d.bitrate,p[intPreferencesKey("minBitrate")]?:d.minBitrate,p[booleanPreferencesKey("adaptive")]?:d.adaptive,
            LightMode.entries.firstOrNull { it.name==p[stringPreferencesKey("light")] }?:d.light,
            p[intPreferencesKey("nightStart")]?:d.nightStart,p[intPreferencesKey("nightEnd")]?:d.nightEnd,
            Corner.entries.firstOrNull { it.name==p[stringPreferencesKey("corner")] }?:d.corner,
            p[floatPreferencesKey("pipWidth")]?:d.pipWidth,p[floatPreferencesKey("pipMaxHeight")]?:d.pipMaxHeight)
    }
    suspend fun save(s:CaptureSettings) {
        require(s.validationErrors().isEmpty()) { s.validationErrors().joinToString("；") }
        store.edit { p ->
            p[intPreferencesKey("width")]=s.width; p[intPreferencesKey("height")]=s.height; p[intPreferencesKey("fps")]=s.fps
            p[intPreferencesKey("bitrate")]=s.bitrate; p[intPreferencesKey("minBitrate")]=s.minBitrate; p[booleanPreferencesKey("adaptive")]=s.adaptive
            p[stringPreferencesKey("light")]=s.light.name; p[intPreferencesKey("nightStart")]=s.nightStart; p[intPreferencesKey("nightEnd")]=s.nightEnd
            p[stringPreferencesKey("corner")]=s.corner.name; p[floatPreferencesKey("pipWidth")]=s.pipWidth; p[floatPreferencesKey("pipMaxHeight")]=s.pipMaxHeight
        }
    }
}
