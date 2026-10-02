package app.lawnchair.backup

import android.content.ContentValues
import android.content.Context
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

/**
 * Replaces the stock default layout with a fixed dock and every installed app on the
 * home screen. Called once, right after the launcher creates an empty database (first
 * open for this user), so later changes made by the user are kept.
 *
 * Home screen:
 * - If every app in [HOME_ORDER] and [VANADIUM] is installed, the apps are laid out in
 *   the original staircase (5/4/3/2 per row) with Vanadium in the bottom-right cell.
 * - Otherwise the installed ones are packed 5 per row in the same order, Vanadium last.
 * - Any other app (except dock apps and hidden apps) follows, alphabetically.
 * Apps start on the second page; the first page stays empty, as in the source setup.
 */
object FirstRunLayout {
    private const val TAG = "FirstRunLayout"

    private const val COLUMNS = 5
    private const val ROWS = 5
    private const val FIRST_SCREEN = 1

    // Each entry lists accepted package names, preferred first.
    private val DOCK = listOf(
        listOf("app.grapheneos.camera"),
        listOf("com.android.gallery3d", "app.grapheneos.gallery"),
        listOf("app.vanadium.browser"),
        listOf("com.android.dialer", "com.google.android.dialer"),
        listOf("com.android.messaging", "com.google.android.apps.messaging"),
    )

    private val HOME_ORDER = listOf(
        // Row 1
        listOf("ch.threema.app.libre", "ch.threema.app"),
        listOf("im.molly.app"),
        listOf("org.sufficientlysecure.keychain"),
        listOf("io.github.nfdz.cryptool"),
        listOf("org.thunderdog.challegram", "org.telegram.messenger"),
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
    private val VANADIUM = listOf("app.vanadium.browser")

    /** Number of [HOME_ORDER] apps on each row of the full staircase layout. */
    private val STAIRCASE_ROWS = listOf(5, 4, 3, 2)

    @JvmStatic
    fun apply(context: Context, db: ModelDbController) {
        try {
            applyInternal(context, db)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply first-run layout, keeping stock layout", e)
        }
    }

    private fun applyInternal(context: Context, db: ModelDbController) {
        val user = Process.myUserHandle()
        val launcherApps = context.getSystemService(LauncherApps::class.java)
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
        val vanadium = find(VANADIUM)
        val used = (listed.filterNotNull() + listOfNotNull(vanadium) + dockApps)
            .map { it.componentName }.toSet()
        val extras = visible
            .filter { it.componentName !in used }
            .sortedBy { it.label.toString().lowercase() }

        var slot: Int
        if (listed.all { it != null } && vanadium != null) {
            var index = 0
            STAIRCASE_ROWS.forEachIndexed { row, count ->
                repeat(count) { col ->
                    insert(db, listed[index++]!!, serial, Favorites.CONTAINER_DESKTOP, FIRST_SCREEN, col, row)
                }
            }
            insert(db, vanadium, serial, Favorites.CONTAINER_DESKTOP, FIRST_SCREEN, COLUMNS - 1, ROWS - 1)
            // Keep the staircase page as-is; anything else starts on the next page.
            slot = COLUMNS * ROWS
        } else {
            slot = 0
            (listed.filterNotNull() + listOfNotNull(vanadium)).forEach { app ->
                insertAtSlot(db, app, serial, slot++)
            }
        }
        extras.forEach { app -> insertAtSlot(db, app, serial, slot++) }
    }

    private fun insertAtSlot(db: ModelDbController, app: LauncherActivityInfo, serial: Long, slot: Int) {
        val perPage = COLUMNS * ROWS
        val inPage = slot % perPage
        insert(
            db,
            app,
            serial,
            Favorites.CONTAINER_DESKTOP,
            screen = FIRST_SCREEN + slot / perPage,
            x = inPage % COLUMNS,
            y = inPage / COLUMNS,
        )
    }

    private fun insert(
        db: ModelDbController,
        app: LauncherActivityInfo,
        serial: Long,
        container: Int,
        screen: Int,
        x: Int,
        y: Int,
    ) {
        val values = ContentValues().apply {
            put(Favorites._ID, db.generateNewItemId())
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
