package com.mdev64.simpletvlauncher

import android.content.ComponentName

/**
 * One launchable app shown in the grid. Immutable; the PackageManager is
 * only touched during scan and icon loading — never during binds.
 */
data class AppItem(
    val label: String,
    val component: ComponentName,
    val isLeanback: Boolean,
    val systemApp: Boolean
) {
    /** Stable identity across scans (used for pins/hidden prefs). */
    val key: String = component.flattenToShortString()
}
