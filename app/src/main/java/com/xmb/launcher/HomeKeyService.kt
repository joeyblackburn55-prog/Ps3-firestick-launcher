package com.xmb.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.io.File

/** Opens XMB over Amazon's home screen, and shows the goal pop-ups on top of any app. */
class HomeKeyService : AccessibilityService() {
    companion object {
        /** Set when the user deliberately opens Amazon Home from XMB, so we don't bounce back. */
        @Volatile var allowAmazonUntil = 0L
        @Volatile var lastLaunch = 0L
        @Volatile var instance: HomeKeyService? = null
    }

    private var watcher: GoalWatcher? = null
    private var sfx: Sfx? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        watcher?.stop()
        Cfg.load(this)
        sfx = Sfx(File(filesDir, "media"))
        watcher = GoalWatcher { g -> GoalToast.showOverlay(this, g, sfx) }.also { it.start() }
    }

    override fun onDestroy() {
        watcher?.stop(); watcher = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (e.packageName?.toString() != "com.amazon.tv.launcher") return
        val now = System.currentTimeMillis()
        if (now < allowAmazonUntil) return
        if (now - lastLaunch < 700) return
        lastLaunch = now
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    override fun onInterrupt() {}
    override fun onKeyEvent(event: KeyEvent): Boolean = false
}
