package dev.framio.meadow

import android.app.Application
import android.content.Context

class MeadowApplication : Application() {
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        state = AppState(this)
    }
}

val Context.app: AppState get() = (applicationContext as MeadowApplication).state
