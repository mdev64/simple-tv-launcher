package com.mdev64.simpletvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [MainActivity] automatically after a TV reboot when the user has
 * enabled "Start Automatically on TV Boot" / set Simple TV Launcher as default.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val prefs = try {
            Prefs(context)
        } catch (_: Exception) {
            null
        }

        val shouldLaunch = (prefs != null && prefs.defaultLauncherEnabled) ||
            DefaultLauncherHelper.isDefaultHome(context)

        if (shouldLaunch) {
            DefaultLauncherHelper.setHomeAliasEnabled(context, true)
            try {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                context.startActivity(launchIntent)
            } catch (_: Exception) { }
        }
    }
}
