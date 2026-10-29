package dev.framio.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.graphics.toColorInt

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

/**
 * Any launchable app on the frame, for the All apps grid. framio apps carry their tile's [subtitle] and [accent]
 * (ARGB); for the others the grid takes the accent from the icon.
 */
data class AppEntry(
    val component: ComponentName,
    val label: String,
    val system: Boolean,
    val subtitle: String = "",
    val accent: Int? = null,
) {
    val pkg: String get() = component.packageName
    val framio: Boolean get() = pkg.startsWith("dev.framio.")
}

/** The apps the home screen opens: Frameo's photos, every framio app installed, and the apps the user pinned. */
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

    /**
     * The shelf: every framio app with a LAUNCHER activity (not this launcher) unless [hidden], plus the other apps
     * [pinned] from All apps; by `order` then title.
     */
    fun discover(context: Context, hidden: Set<String> = emptySet(), pinned: Set<String> = emptySet()): List<AppTile> {
        val pm = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launchable, PackageManager.GET_META_DATA)
            .filter {
                val p = it.activityInfo.packageName
                p != context.packageName && p != FRAMEO && if (p.startsWith(PREFIX)) p !in hidden else p in pinned
            }
            .distinctBy { it.activityInfo.packageName } // one tile per app
            .map { info ->
                val a = info.activityInfo
                val meta = a.metaData
                val known = KNOWN[a.packageName]
                AppTile(
                    component = ComponentName(a.packageName, a.name),
                    title = info.loadLabel(pm).toString(),
                    subtitle = subtitleOf(a.packageName, meta),
                    accent = accentOf(a.packageName, meta),
                    // getInt: android:value="40" is stored as an Int
                    order = meta?.getInt(META_ORDER, -1)?.takeIf { it >= 0 } ?: known?.third ?: 100,
                    icon = if (a.iconResource != 0 || a.applicationInfo.icon != 0) info.loadIcon(pm) else null,
                )
            }
            .sortedWith(compareBy({ it.order }, { it.title.lowercase() }))
    }

    /** Every launchable app but this launcher, by name: Android's Settings included, the frame's escape hatch. */
    fun all(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launchable, PackageManager.GET_META_DATA)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                val a = it.activityInfo
                val framio = a.packageName.startsWith(PREFIX)
                AppEntry(
                    ComponentName(a.packageName, a.name),
                    it.loadLabel(pm).toString(),
                    a.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    subtitle = if (framio) subtitleOf(a.packageName, a.metaData) else "",
                    accent = if (framio) accentOf(a.packageName, a.metaData) else null,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun subtitleOf(pkg: String, meta: android.os.Bundle?): String =
        meta?.getString(META_SUBTITLE) ?: KNOWN[pkg]?.first ?: ""

    private fun accentOf(pkg: String, meta: android.os.Bundle?): Int =
        meta?.getString(META_ACCENT)?.let(::parseColor) ?: KNOWN[pkg]?.second ?: PALETTE[pkg.hashCode().mod(PALETTE.size)]

    fun icon(context: Context, component: ComponentName): Drawable? = try {
        context.packageManager.getActivityIcon(component)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    /** Opens what [Config.startup] names: [Config.PHOTOS] or a flattened component. */
    fun openTarget(context: Context, target: String): Boolean =
        if (target == Config.PHOTOS) {
            openPhotos(context)
        } else {
            ComponentName.unflattenFromString(target)?.let { openComponent(context, it) } == true
        }

    /** A name for [Config.startup], or null when it no longer exists (the app was removed). */
    fun targetLabel(context: Context, target: String?): String? = when (target) {
        null -> null
        Config.PHOTOS -> if (isInstalled(context, FRAMEO)) "Photos" else null
        else -> ComponentName.unflattenFromString(target)?.takeIf { isInstalled(context, it.packageName) }?.let { label(context, it.packageName) }
    }

    fun openComponent(context: Context, component: ComponentName) = launch(
        context,
        component.packageName,
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component),
    )

    /** Android's own page for the app: storage, permissions, Force stop, Uninstall. */
    fun openInfo(context: Context, pkg: String) = startSafely(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)),
    )

    /** Android's confirmation dialog, so nothing is removed by a stray tap. */
    fun uninstall(context: Context, pkg: String) = startSafely(context, Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))

    fun openSettings(context: Context, action: String = Settings.ACTION_SETTINGS) = startSafely(context, Intent(action))

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not start $intent", e)
        false
    }

    private fun parseColor(s: String): Int? = try {
        s.trim().toColorInt()
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
    fun open(context: Context, tile: AppTile) = openComponent(context, tile.component)

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
