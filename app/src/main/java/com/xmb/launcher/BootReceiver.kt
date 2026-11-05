package com.xmb.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens XMB when the stick finishes booting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
