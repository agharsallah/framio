package dev.framio.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** The apps the home screen tiles open. */
object Apps {

    const val FRAMEO = "net.frameo.frame"
    const val HELLO = "dev.framio.hello"

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Frameo only exports its entry activity (AWaitForDeferredInitCompleted); the slideshow
     * (AScreenSlidePager) is private. Android brings an existing task to the front untouched only
     * if the intent equals the task's root intent. Frameo's task was started as HOME
     * (MAIN + HOME), so we send exactly that. A LAUNCHER intent instead stacks a new entry
     * activity on top, which re-shows Frameo's red "use the power off button" notice every time.
     */
    fun openPhotos(context: Context) = launch(
        context,
        FRAMEO,
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(FRAMEO, "$FRAMEO.ui.activities.AWaitForDeferredInitCompleted")),
    ) || launch(context, FRAMEO) // fallback if Frameo renames its entry activity

    fun openHello(context: Context) = launch(
        context,
        HELLO,
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(HELLO, "$HELLO.MainActivity")),
    )

    private fun launch(context: Context, pkg: String, explicit: Intent? = null): Boolean {
        val intent = explicit ?: context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "$pkg has no launch intent")
            return false
        }
        // NEW_TASK + RESET_TASK_IF_NEEDED = what a launcher sends: reuse the app's task if it exists
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: RuntimeException) { // ActivityNotFound / SecurityException
            Log.w(TAG, "Could not open $pkg", e)
            false
        }
    }

    private const val TAG = "Launcher"
}
