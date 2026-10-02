package app.lawnchair.backup

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import com.android.launcher3.LauncherFiles
import java.io.File
import java.util.concurrent.Executors

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
            }
            marker.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply default settings", e)
        }
    }

    /** Sets the bundled wallpaper once, off the main thread. */
    fun applyWallpaper(context: Context) {
        val pending = File(context.noBackupFilesDir, WALLPAPER_PENDING_MARKER)
        if (!pending.exists()) return
        Executors.newSingleThreadExecutor().execute {
            try {
                context.assets.open("$ASSET_DIR/$WALLPAPER_FILE_NAME").use {
                    WallpaperManager.getInstance(context).setStream(it)
                }
                pending.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply default wallpaper", e)
            }
        }
    }

    private fun copyAsset(context: Context, name: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open("$ASSET_DIR/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }
}
