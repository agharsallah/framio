package dev.framio.pomodoro

import android.app.Application
import android.os.StrictMode
import dev.framio.pomodoro.di.AppContainer
import dev.framio.pomodoro.platform.CrashGuard

class PomodoroApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Before anything can crash: a crash in Blackout must not leave the frame dark
        CrashGuard.install(this) { container.displayPower.restoreBlocking() }
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
    }
}
