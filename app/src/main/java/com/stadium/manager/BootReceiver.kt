package com.stadium.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are erased when the phone restarts: put the booking reminders back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.reschedule(ctx)
    }
}
