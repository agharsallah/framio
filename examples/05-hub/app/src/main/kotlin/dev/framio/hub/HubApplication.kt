package dev.framio.hub

import android.app.Application
import android.os.StrictMode
import dev.framio.hub.di.AppContainer
import dev.framio.hub.service.CrashGuard

class HubApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Before anything can crash: restore the backlight, write the report, count the crash
        CrashGuard.install(this) { container.display.restoreBlocking() }
        if (BuildConfig.DEBUG) {
            // Disk or network on the main thread, leaked closeables: logged and flashed in debug builds
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().penaltyFlashScreen().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedRegistrationObjects().penaltyLog().build())
        }
    }
}
