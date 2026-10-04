package com.wusui.rtmpcapture

import android.app.Application
import androidx.room.Room
import com.wusui.rtmpcapture.data.*
import com.wusui.rtmpcapture.service.CaptureState
import com.wusui.rtmpcapture.ui.MainViewModel
import kotlinx.coroutines.*
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.core.module.dsl.viewModel

class CaptureApp:Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@CaptureApp)
            modules(module {
                single { Room.databaseBuilder(get(),CaptureDatabase::class.java,"capture.db").build() }
                single { get<CaptureDatabase>().dao() }
                single { SecretStore() }; single { SettingsStore(get()) }; single { AuthClient(get()) }
                single { CaptureState() }
                viewModel { MainViewModel(get(),get(),get(),get(),get(),get()) }
            })
        }
        // The service never restarts itself; stale sessions can be identified after process death.
        CoroutineScope(Dispatchers.IO).launch { runCatching { org.koin.core.context.GlobalContext.get().get<CaptureDao>().recoverInterrupted(System.currentTimeMillis()) } }
    }
}
