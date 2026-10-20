package dev.framio.meadow

import android.app.Application
import android.content.Context

class MeadowApplication : Application() {
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        dev.framio.meadow.core.Diagnostics.install(filesDir) // first: so a crash while starting is recorded too
        state = AppState(this)
    }
}

val Context.app: AppState get() = (applicationContext as MeadowApplication).state
