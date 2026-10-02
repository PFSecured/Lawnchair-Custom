package app.lawnchair.backup

import android.app.WallpaperManager
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.android.launcher3.LauncherFiles
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seeds a fresh install with the settings and wallpaper bundled in
 * `assets/first_run_defaults` (taken from a Lawnchair backup, without its
 * home screen layout). Runs once per install/user; anything the user changes
 * afterwards is kept.
 */
object FirstRunDefaults {
    private const val TAG = "FirstRunDefaults"
    private const val ASSET_DIR = "first_run_defaults"
    private const val PREFS_FILE_NAME = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"
    private const val DATASTORE_FILE_NAME = "preferences.preferences_pb"
    private const val WALLPAPER_FILE_NAME = "wallpaper.png"
    private const val SEEDED_MARKER = "first_run_defaults_applied"
    private const val WALLPAPER_PENDING_MARKER = "first_run_wallpaper_pending"

    /**
     * Copies the bundled preference files into place. Must run before anything
     * reads SharedPreferences or the preferences DataStore, so it is called from
     * [app.lawnchair.LawnchairApp.attachBaseContext].
     */
    fun applySettings(context: Context) {
        // Before the user is unlocked, app data storage isn't available; try again later.
        if (!isUserUnlocked(context)) {
            Log.i(TAG, "User locked, deferring default settings")
            return
        }
        val marker = File(context.noBackupFilesDir, SEEDED_MARKER)
        if (marker.exists()) return
        try {
            val dataDir = context.dataDir
            val prefsFile = File(dataDir, "shared_prefs/$PREFS_FILE_NAME")
            val dataStoreFile = File(context.filesDir, "datastore/$DATASTORE_FILE_NAME")
            // Only seed a genuinely fresh install; never overwrite existing settings.
            if (!prefsFile.exists() && !dataStoreFile.exists()) {
                copyAsset(context, PREFS_FILE_NAME, prefsFile)
                copyAsset(context, DATASTORE_FILE_NAME, dataStoreFile)
                File(context.noBackupFilesDir, WALLPAPER_PENDING_MARKER).createNewFile()
                Log.i(TAG, "Applied default settings")
            } else {
                Log.i(TAG, "Existing settings found, defaults not applied")
            }
            marker.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply default settings", e)
        }
    }

    /**
     * Sets the bundled wallpaper (home and lock screen) once, off the main thread.
     * Called when the launcher opens. Waits until the user profile is unlocked and has
     * finished its setup screens (setup can reset the wallpaper), then applies it a
     * single time; it is never applied again, so a wallpaper the user picks later stays.
     */
    fun applyWallpaper(context: Context) {
        if (!isUserUnlocked(context) || !isUserSetupComplete(context)) return
        // Settings may have been deferred while the user was locked.
        applySettings(context)
        val pending = File(context.noBackupFilesDir, WALLPAPER_PENDING_MARKER)
        if (!pending.exists() || !wallpaperInFlight.compareAndSet(false, true)) return
        Executors.newSingleThreadExecutor().execute {
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)
                if (wallpaperManager.wallpaperInfo != null) {
                    // A live wallpaper was chosen already; leave it alone.
                    pending.delete()
                    Log.i(TAG, "Live wallpaper already set, default wallpaper skipped")
                    return@execute
                }
                context.assets.open("$ASSET_DIR/$WALLPAPER_FILE_NAME").use {
                    wallpaperManager.setStream(
                        it,
                        null,
                        true,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                    )
                }
                pending.delete()
                Log.i(TAG, "Applied default wallpaper")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply default wallpaper", e)
            } finally {
                wallpaperInFlight.set(false)
            }
        }
    }

    private val wallpaperInFlight = AtomicBoolean(false)

    private fun isUserSetupComplete(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, "user_setup_complete", 0) == 1

    private fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    private fun copyAsset(context: Context, name: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open("$ASSET_DIR/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }
}
