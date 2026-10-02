package app.lawnchair.ui.preferences.data.liveinfo

import androidx.compose.runtime.Composable

/**
 * Offline build: never fetches announcements from lawnchair.app.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun SyncLiveInformation(
    liveInformationManager: LiveInformationManager = liveInformationManager(),
) = Unit
