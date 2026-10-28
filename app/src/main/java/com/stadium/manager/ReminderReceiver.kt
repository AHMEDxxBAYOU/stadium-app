package com.stadium.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "تذكير بموعد حجز"
        val body = intent.getStringExtra("body") ?: ""
        val id = intent.getStringExtra("id") ?: body
        Notifier.show(ctx, ("rem_" + id).hashCode(), Notifier.CH_REMINDERS, title, body)
    }
}
