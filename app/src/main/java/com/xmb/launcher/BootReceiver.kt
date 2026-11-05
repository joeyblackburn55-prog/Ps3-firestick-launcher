package com.xmb.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * At boot: turn the Home-button service back on (Fire OS switches third-party accessibility
 * services off on reboot), then try to open XMB. If Android blocks the direct launch,
 * HomeKeyService opens XMB itself as soon as it is connected.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        HomeGuard.ensureEnabled(c)
        runCatching { c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Keeps HomeKeyService enabled. Needs: adb shell pm grant com.xmb.launcher android.permission.WRITE_SECURE_SETTINGS */
object HomeGuard {
    fun canFix(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(c: Context): Boolean {
        val me = ComponentName(c, HomeKeyService::class.java).flattenToString()
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.equals(me, ignoreCase = true) } &&
            Settings.Secure.getInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }

    /** Re-enables the service if it was switched off. Keeps any other enabled services. */
    fun ensureEnabled(c: Context) {
        if (!canFix(c) || isEnabled(c)) return
        runCatching {
            val cr = c.contentResolver
            val me = ComponentName(c, HomeKeyService::class.java).flattenToString()
            val others = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }
}
