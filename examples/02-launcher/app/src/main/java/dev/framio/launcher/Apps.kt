package dev.framio.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * One tile of the home screen: a framio app found on the frame.
 * @param accent ARGB, from the app's `dev.framio.tile.accent` or picked from its package name
 * @param icon the app's own icon, null when it has none (the tile then shows a monogram)
 */
data class AppTile(
    val component: ComponentName,
    val title: String,
    val subtitle: String,
    val accent: Int,
    val order: Int,
    val icon: Drawable?,
) {
    val pkg: String get() = component.packageName
}

/** The apps the home screen opens: Frameo's photos, and every framio app installed. */
object Apps {

    const val FRAMEO = "net.frameo.frame"
    private const val PREFIX = "dev.framio."

    /**
     * What a framio app can put on its LAUNCHER activity to style its tile (all optional):
     *
     *     <meta-data android:name="dev.framio.tile.subtitle" android:value="Prayer times" />
     *     <meta-data android:name="dev.framio.tile.accent" android:value="#6CC79A" />
     *     <meta-data android:name="dev.framio.tile.order" android:value="40" />
     *
     * Without them the tile shows the app's label, its icon (or first letter) and a colour of its own.
     */
    private const val META_SUBTITLE = "dev.framio.tile.subtitle"
    private const val META_ACCENT = "dev.framio.tile.accent"
    private const val META_ORDER = "dev.framio.tile.order"

    /** Defaults for the apps that predate the metadata, so their tiles look as they always did. */
    private val KNOWN = mapOf(
        "dev.framio.hello" to Triple("Example 01 · WebView", 0xFF5BC6B4.toInt(), 10),
        "dev.framio.lab" to Triple("Example 03 · Hardware", 0xFFA38BE8.toInt(), 30),
    )

    /** Accents for apps that don't pick one: muted enough to sit next to each other. */
    private val PALETTE = intArrayOf(0xFF6CC79A.toInt(), 0xFF7DB4F0.toInt(), 0xFFF08A7D.toInt(), 0xFFE7C66B.toInt(), 0xFFC792EA.toInt(), 0xFF5BC6B4.toInt())

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Every framio app with a LAUNCHER activity (not this launcher), by `order` then title. */
    fun discover(context: Context): List<AppTile> {
        val pm = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launchable, PackageManager.GET_META_DATA)
            .filter { it.activityInfo.packageName.startsWith(PREFIX) && it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName } // one tile per app
            .map { info ->
                val a = info.activityInfo
                val meta = a.metaData
                val known = KNOWN[a.packageName]
                AppTile(
                    component = ComponentName(a.packageName, a.name),
                    title = info.loadLabel(pm).toString(),
                    subtitle = meta?.getString(META_SUBTITLE) ?: known?.first ?: "",
                    accent = meta?.getString(META_ACCENT)?.let(::parseColor)
                        ?: known?.second
                        ?: PALETTE[a.packageName.hashCode().mod(PALETTE.size)],
                    // getInt: android:value="40" is stored as an Int
                    order = meta?.getInt(META_ORDER, -1)?.takeIf { it >= 0 } ?: known?.third ?: 100,
                    icon = if (a.iconResource != 0 || a.applicationInfo.icon != 0) info.loadIcon(pm) else null,
                )
            }
            .sortedWith(compareBy({ it.order }, { it.title.lowercase() }))
    }

    private fun parseColor(s: String): Int? = try {
        android.graphics.Color.parseColor(s.trim())
    } catch (_: IllegalArgumentException) {
        null
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

    /** Exactly the intent a launcher sends: an app already running comes forward as it was. */
    fun open(context: Context, tile: AppTile) = launch(
        context,
        tile.pkg,
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(tile.component),
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
