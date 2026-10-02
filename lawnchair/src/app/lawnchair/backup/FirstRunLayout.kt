package app.lawnchair.backup

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.util.Log
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ComponentKey
import com.patrykmichalik.opto.core.firstBlocking
import java.io.File

/**
 * Replaces the stock default layout with a fixed dock and every installed app on the
 * home screen. Called once, right after the launcher creates an empty database (first
 * open for this user), so later changes made by the user are kept.
 *
 * Home screen:
 * - If every app in [HOME_ORDER] and [UPDATER] is installed, the apps are laid out in
 *   the original staircase (5/4/3/2 per row) with Obtainium in the bottom-right cell.
 * - Otherwise the installed ones are packed 5 per row in the same order, Obtainium last.
 * - Any other user-installed app (not built-in, dock or hidden) fills the free cells,
 *   alphabetically.
 * Everything goes on the first page; further pages are only created once it is full.
 */
object FirstRunLayout {
    private const val TAG = "FirstRunLayout"
    private const val APPLIED_MARKER = "first_run_layout_applied"
    private const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

    private const val COLUMNS = 5
    private const val ROWS = 5
    private const val SCREEN = 0

    // Each entry lists accepted package names, preferred first.
    private val DOCK = listOf(
        listOf("app.grapheneos.camera"),
        listOf("com.android.gallery3d", "app.grapheneos.gallery"),
        listOf("app.vanadium.browser"),
        listOf("com.android.dialer", "com.google.android.dialer"),
        listOf("com.android.messaging", "com.google.android.apps.messaging"),
    )

    private val THREEMA = listOf("ch.threema.app.libre", "ch.threema.app")

    private val HOME_ORDER = listOf(
        // Row 1
        THREEMA,
        listOf("im.molly.app"),
        listOf("org.sufficientlysecure.keychain"),
        listOf("io.github.nfdz.cryptool"),
        listOf("org.thunderdog.challegram"), // Telegram X only; regular Telegram is an "other" app
        // Row 2
        listOf("chat.simplex.app"),
        listOf("org.briarproject.briar.android"),
        listOf("network.loki.messenger"),
        listOf("com.whatsapp"),
        // Row 3
        listOf("de.tutao.tutanota"),
        listOf("com.wallet.crypto.trustapp"),
        listOf("com.aurora.store"),
        // Row 4
        listOf("net.mullvad.mullvadvpn"),
        listOf("com.standardnotes"),
    )
    private val UPDATER = listOf("dev.imranr.obtainium.fdroid", "dev.imranr.obtainium")

    /** Number of [HOME_ORDER] apps on each row of the full staircase layout. */
    private val STAIRCASE_ROWS = listOf(5, 4, 3, 2)

    @JvmStatic
    fun apply(context: Context, db: ModelDbController) {
        // Once per install: a later empty database (e.g. after a grid size change)
        // must never bring this layout back over the user's own arrangement.
        val marker = File(context.noBackupFilesDir, APPLIED_MARKER)
        if (marker.exists()) return
        try {
            applyInternal(context, db)
            marker.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply first-run layout, keeping stock layout", e)
        }
    }

    private fun applyInternal(context: Context, db: ModelDbController) {
        val user = Process.myUserHandle()
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        val activities = launcherApps.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
        if (activities.isEmpty()) return

        val hidden = runCatching {
            PreferenceManager2.getInstance(context).hiddenApps.firstBlocking()
        }.getOrDefault(emptySet())
        val visible = activities.filter {
            ComponentKey(it.componentName, user).toString() !in hidden
        }
        val byPackage = visible.groupBy { it.componentName.packageName }
        fun find(candidates: List<String>): LauncherActivityInfo? =
            candidates.firstNotNullOfOrNull { byPackage[it]?.first() }

        val serial = db.getSerialNumberForUser(user)
        // One transaction: if anything fails, the stock layout is left untouched.
        db.newTransaction().use { transaction ->
            db.delete(Favorites.TABLE_NAME, null, null)
            writeLayout(db, visible, ::find, serial)
            transaction.commit()
        }
    }

    private fun writeLayout(
        db: ModelDbController,
        visible: List<LauncherActivityInfo>,
        find: (List<String>) -> LauncherActivityInfo?,
        serial: Long,
    ) {
        // Dock: installed apps left to right, no gaps.
        val dockApps = DOCK.mapNotNull { find(it) }
        dockApps.forEachIndexed { index, app ->
            insert(db, app, serial, Favorites.CONTAINER_HOTSEAT, screen = index, x = index, y = 0)
        }

        val listed = HOME_ORDER.map { find(it) }
        val updater = find(UPDATER)
        // Threema and Threema Libre both installed: they share the first spot in a folder.
        val threemaApps = THREEMA.mapNotNull { pkg -> visible.firstOrNull { it.componentName.packageName == pkg } }
        val used = (listed.filterNotNull() + threemaApps + listOfNotNull(updater) + dockApps)
            .map { it.componentName }.toSet()
        // Built-in (system) apps stay in the drawer only; just user-installed ones are placed.
        val extras = visible
            .filter { it.componentName !in used }
            .filter { it.applicationInfo.flags and SYSTEM_FLAGS == 0 }
            .sortedBy { it.label.toString().lowercase() }

        // Single page only: cells are numbered 0..24 in reading order.
        val taken = BooleanArray(COLUMNS * ROWS)
        fun place(app: LauncherActivityInfo, cell: Int) {
            taken[cell] = true
            val x = cell % COLUMNS
            val y = cell / COLUMNS
            if (app === listed[0] && threemaApps.size > 1) {
                insertFolder(db, "Threema", threemaApps, serial, x, y)
            } else {
                insert(db, app, serial, Favorites.CONTAINER_DESKTOP, SCREEN, x, y)
            }
        }

        if (listed.all { it != null } && updater != null) {
            var index = 0
            STAIRCASE_ROWS.forEachIndexed { row, count ->
                repeat(count) { col -> place(listed[index++]!!, row * COLUMNS + col) }
            }
            place(updater, COLUMNS * ROWS - 1)
        } else {
            (listed.filterNotNull() + listOfNotNull(updater)).forEachIndexed { cell, app ->
                if (cell < taken.size) place(app, cell)
            }
        }
        // Other apps fill the remaining free cells on the first page; only if that
        // page is full do they continue onto further pages.
        val free = taken.indices.filter { !taken[it] }.iterator()
        var overflow = 0
        extras.forEach { app ->
            if (free.hasNext()) {
                place(app, free.next())
            } else {
                val perPage = COLUMNS * ROWS
                val cell = overflow % perPage
                val screen = SCREEN + 1 + overflow / perPage
                insert(db, app, serial, Favorites.CONTAINER_DESKTOP, screen, cell % COLUMNS, cell / COLUMNS)
                overflow++
            }
        }
    }

    private fun insertFolder(
        db: ModelDbController,
        title: String,
        apps: List<LauncherActivityInfo>,
        serial: Long,
        x: Int,
        y: Int,
    ) {
        val folderId = db.generateNewItemId()
        val values = ContentValues().apply {
            put(Favorites._ID, folderId)
            put(Favorites.TITLE, title)
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
            put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
            put(Favorites.SCREEN, SCREEN)
            put(Favorites.CELLX, x)
            put(Favorites.CELLY, y)
            put(Favorites.SPANX, 1)
            put(Favorites.SPANY, 1)
        }
        db.insert(Favorites.TABLE_NAME, values)
        apps.forEachIndexed { rank, app ->
            insert(db, app, serial, folderId, SCREEN, x = rank, y = 0, rank = rank)
        }
    }

    private fun insert(
        db: ModelDbController,
        app: LauncherActivityInfo,
        serial: Long,
        container: Int,
        screen: Int,
        x: Int,
        y: Int,
        rank: Int = 0,
    ) {
        val values = ContentValues().apply {
            put(Favorites._ID, db.generateNewItemId())
            put(Favorites.RANK, rank)
            put(Favorites.TITLE, app.label.toString())
            put(Favorites.INTENT, AppInfo.makeLaunchIntent(app.componentName).toUri(0))
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
            put(Favorites.CONTAINER, container)
            put(Favorites.SCREEN, screen)
            put(Favorites.CELLX, x)
            put(Favorites.CELLY, y)
            put(Favorites.SPANX, 1)
            put(Favorites.SPANY, 1)
            put(Favorites.PROFILE_ID, serial)
        }
        db.insert(Favorites.TABLE_NAME, values)
    }
}
