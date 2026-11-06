package com.mdev64.simpletvlauncher

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/**
 * Manages taking over the TV's default HOME launcher role, persisting across
 * restarts, and cleanly undoing/restoring back to a valid installed launcher
 * without ever stranding the user on `.FallbackHome` (black screen).
 */
object DefaultLauncherHelper {

    private const val HOME_ALIAS_CLASS = "com.mdev64.simpletvlauncher.HomeLauncherAlias"
    private const val CHOOSER_TRIGGER_CLASS = "com.mdev64.simpletvlauncher.HomeChooserTrigger"

    data class LauncherInfo(
        val packageName: String,
        val activityName: String,
        val label: String
    )

    private fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    /** True when Simple TV Launcher currently holds the system default HOME role. */
    fun isDefaultHome(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val rm = ctx.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (rm.isRoleHeld(RoleManager.ROLE_HOME)) return true
                }
            } catch (_: Exception) { }
        }
        val resolved = try {
            ctx.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            null
        }
        return resolved?.activityInfo?.packageName == ctx.packageName
    }

    /** Returns the currently active default HOME launcher, or null if unresolved/chooser. */
    fun currentDefaultHome(ctx: Context): LauncherInfo? {
        val pm = ctx.packageManager
        val resolved = try {
            pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            null
        } ?: return null

        val ai = resolved.activityInfo ?: return null
        val pkg = ai.packageName ?: return null
        val cls = ai.name ?: return null
        if (isIgnoredHomeStub(ctx, pkg, cls)) return null
        if (!isPackageAndComponentEnabled(pm, pkg, cls)) return null

        val label = resolveLabel(pm, ai.applicationInfo, resolved)
        return LauncherInfo(pkg, cls, label)
    }

    /** Lists all other installed and ENABLED TV home launchers (excluding Simple TV Launcher and OS stubs). */
    fun otherInstalledLaunchers(ctx: Context): List<LauncherInfo> {
        val pm = ctx.packageManager
        val list = try {
            pm.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            emptyList()
        }
        val out = ArrayList<LauncherInfo>()
        val seen = HashSet<String>()
        for (ri in list) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            val cls = ai.name ?: continue
            if (isIgnoredHomeStub(ctx, pkg, cls)) continue
            if (!isPackageAndComponentEnabled(pm, pkg, cls)) continue
            if (!seen.add(pkg)) continue
            val label = resolveLabel(pm, ai.applicationInfo, ri)
            out.add(LauncherInfo(pkg, cls, label))
        }
        return out
    }

    /**
     * Lists stock/system launchers that are installed on the TV but currently disabled
     * (e.g., `com.google.android.apps.tv.launcherx` disabled via ADB/user settings).
     */
    fun disabledSystemLaunchers(ctx: Context): List<LauncherInfo> {
        val pm = ctx.packageManager
        val list = try {
            pm.queryIntentActivities(homeIntent(), PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: Exception) {
            emptyList()
        }
        val enabledPkgs = otherInstalledLaunchers(ctx).map { it.packageName }.toHashSet()
        val out = ArrayList<LauncherInfo>()
        val seen = HashSet<String>()
        for (ri in list) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            val cls = ai.name ?: continue
            if (isIgnoredHomeStub(ctx, pkg, cls)) continue
            if (pkg in enabledPkgs) continue
            if (isPackageAndComponentEnabled(pm, pkg, cls)) continue
            if (!seen.add(pkg)) continue
            val label = resolveLabel(pm, ai.applicationInfo, ri)
            out.add(LauncherInfo(pkg, cls, label))
        }
        return out
    }

    private fun isIgnoredHomeStub(ctx: Context, pkg: String, cls: String): Boolean {
        return pkg == ctx.packageName ||
            pkg == "android" ||
            pkg == "com.android.tv.settings" ||
            pkg == "com.android.settings" ||
            cls.contains("ResolverActivity") ||
            cls.endsWith(".FallbackHome")
    }

    private fun isPackageAndComponentEnabled(pm: PackageManager, pkg: String, cls: String): Boolean {
        return try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            if (!appInfo.enabled) return false
            val appState = pm.getApplicationEnabledSetting(pkg)
            if (appState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                appState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                appState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                return false
            }
            val compState = pm.getComponentEnabledSetting(ComponentName(pkg, cls))
            compState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                compState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveLabel(
        pm: PackageManager,
        appInfo: ApplicationInfo?,
        ri: android.content.pm.ResolveInfo
    ): String {
        return try {
            val appLabel = appInfo?.let { pm.getApplicationLabel(it).toString() }.orEmpty()
            if (appLabel.isNotBlank()) appLabel else ri.loadLabel(pm).toString()
        } catch (_: Exception) {
            ri.activityInfo?.packageName ?: "Launcher"
        }
    }

    /**
     * Resolves the best available previous/alternative launcher that is ACTUALLY installed
     * and enabled right now. If the previously saved launcher was uninstalled or disabled,
     * prunes the stale preference and falls back to any other enabled launcher (e.g. stock).
     */
    fun getPreviousLauncher(ctx: Context, prefs: Prefs): LauncherInfo? {
        val current = currentDefaultHome(ctx)
        if (current != null && current.packageName != ctx.packageName) {
            return current
        }

        val others = otherInstalledLaunchers(ctx)
        val savedPkg = prefs.previousLauncherPkg
        if (!savedPkg.isNullOrBlank()) {
            val match = others.firstOrNull { it.packageName == savedPkg }
            if (match != null) {
                return match
            }
            // Saved launcher was uninstalled or disabled — clear stale record
            prefs.previousLauncherPkg = null
            prefs.previousLauncherClass = null
            prefs.previousLauncherLabel = null
        }

        return others.firstOrNull()
    }

    /** Records the active third-party/OEM launcher before Simple TV Launcher takes over. */
    fun rememberPreviousLauncherIfNeeded(ctx: Context, prefs: Prefs) {
        val current = currentDefaultHome(ctx)
        if (current != null && current.packageName != ctx.packageName) {
            prefs.previousLauncherPkg = current.packageName
            prefs.previousLauncherClass = current.activityName
            prefs.previousLauncherLabel = current.label
            return
        }
        // Validate that any previously saved launcher still exists and is enabled
        val others = otherInstalledLaunchers(ctx)
        val savedPkg = prefs.previousLauncherPkg
        if (!savedPkg.isNullOrBlank() && others.none { it.packageName == savedPkg }) {
            prefs.previousLauncherPkg = null
            prefs.previousLauncherClass = null
            prefs.previousLauncherLabel = null
        }
        if (prefs.previousLauncherPkg.isNullOrBlank()) {
            others.firstOrNull()?.let { fallback ->
                prefs.previousLauncherPkg = fallback.packageName
                prefs.previousLauncherClass = fallback.activityName
                prefs.previousLauncherLabel = fallback.label
            }
        }
    }

    fun setHomeAliasEnabled(ctx: Context, enabled: Boolean) {
        try {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx.packageName, HOME_ALIAS_CLASS),
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) { }
    }

    private fun pulseChooserTrigger(ctx: Context, enabled: Boolean) {
        try {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx.packageName, CHOOSER_TRIGGER_CLASS),
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) { }
    }

    /**
     * Initiates making Simple TV Launcher the default launcher:
     * 1. Remembers the current launcher for Undo/Restore.
     * 2. Enables `HomeLauncherAlias` so our app qualifies for `ROLE_HOME`.
     * 3. Requests `RoleManager.ROLE_HOME` via the system role dialog, with automatic
     *    fallback to the system Home chooser / Home settings screen.
     */
    fun requestMakeDefaultLauncher(
        activity: Activity,
        prefs: Prefs,
        requestCode: Int,
        onStarted: () -> Unit
    ) {
        rememberPreviousLauncherIfNeeded(activity, prefs)
        prefs.defaultLauncherEnabled = true
        prefs.launchOnBoot = true

        setHomeAliasEnabled(activity, true)
        pulseChooserTrigger(activity, false)

        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (isDefaultHome(activity)) {
                Toast.makeText(
                    activity,
                    "Simple TV Launcher is now your default launcher",
                    Toast.LENGTH_SHORT
                ).show()
                onStarted()
                return@postDelayed
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val rm = activity.getSystemService(RoleManager::class.java)
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                        val intent = rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
                        onStarted()
                        activity.startActivityForResult(intent, requestCode)
                        return@postDelayed
                    }
                } catch (_: Exception) { }
            }

            onStarted()
            openHomeSelectorFallback(activity)
        }, 220L)
    }

    fun openHomeSelectorFallback(activity: Activity) {
        setHomeAliasEnabled(activity, true)
        try {
            val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS)
            if (homeSettings.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(homeSettings)
                return
            }
        } catch (_: Exception) { }

        pulseChooserTrigger(activity, true)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val chooser = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(chooser)
            } catch (_: Exception) {
                Toast.makeText(
                    activity,
                    "Unable to open system home selector on this TV",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                Handler(Looper.getMainLooper()).postDelayed({
                    pulseChooserTrigger(activity.applicationContext, false)
                }, 1500L)
            }
        }, 180L)
    }

    /** Opens the system App Info page for a disabled stock launcher so the user can enable it. */
    fun openLauncherAppDetails(activity: Activity, pkg: String) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, "Unable to open App Info", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Undoes default launcher status and switches back to [target] (or the best available
     * installed launcher). Never disables `HomeLauncherAlias` if no other enabled launcher
     * exists on the TV, preventing the `.FallbackHome` black screen.
     */
    fun restorePreviousLauncher(
        activity: Activity,
        prefs: Prefs,
        target: LauncherInfo? = getPreviousLauncher(activity, prefs)
    ) {
        val enabledOthers = otherInstalledLaunchers(activity)
        val chosen = if (target != null && enabledOthers.any { it.packageName == target.packageName }) {
            target
        } else {
            enabledOthers.firstOrNull()
        }

        if (chosen == null) {
            // Safety guard: never disable our HOME alias when no other launcher is enabled!
            setHomeAliasEnabled(activity, true)
            val disabledStock = disabledSystemLaunchers(activity).firstOrNull()
            if (disabledStock != null) {
                Toast.makeText(
                    activity,
                    "Enable ${disabledStock.label} first before switching launchers",
                    Toast.LENGTH_LONG
                ).show()
                openLauncherAppDetails(activity, disabledStock.packageName)
            } else {
                Toast.makeText(
                    activity,
                    "No other active launcher is installed on this TV",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        prefs.defaultLauncherEnabled = false
        prefs.launchOnBoot = false

        try {
            @Suppress("DEPRECATION")
            activity.packageManager.clearPackagePreferredActivities(activity.packageName)
        } catch (_: Exception) { }

        pulseChooserTrigger(activity, false)
        setHomeAliasEnabled(activity, false)

        Toast.makeText(activity, "Switching to ${chosen.label}…", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            var launched = false
            try {
                val explicit = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(chosen.packageName, chosen.activityName)
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                activity.startActivity(explicit)
                launched = true
            } catch (_: Exception) {
                try {
                    val pkgIntent = activity.packageManager.getLaunchIntentForPackage(chosen.packageName)
                    if (pkgIntent != null) {
                        pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(pkgIntent)
                        launched = true
                    }
                } catch (_: Exception) { }
            }
            if (!launched) {
                // Re-enable our HOME alias if launching the target failed so the user is never stranded
                setHomeAliasEnabled(activity, true)
            } else {
                activity.finish()
            }
        }, 250L)
    }
}
