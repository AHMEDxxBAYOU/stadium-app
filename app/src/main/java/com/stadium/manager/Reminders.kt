package com.stadium.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray

/** Booking reminders scheduled with the phone's alarm clock: they fire even when the app is closed. */
object Reminders {
    private const val PREFS = "stadium_reminders"
    private const val BASE_RC = 7000
    private const val MAX = 60

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun schedule(ctx: Context, json: String) {
        cancelAll(ctx)
        prefs(ctx).edit().putString("list", json).apply()
        apply(ctx, json)
    }

    fun reschedule(ctx: Context) {
        val json = prefs(ctx).getString("list", null) ?: return
        cancelAll(ctx)
        apply(ctx, json)
    }

    private fun apply(ctx: Context, json: String) {
        val arr = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        var n = 0
        for (i in 0 until arr.length()) {
            if (n >= MAX) break
            val o = arr.optJSONObject(i) ?: continue
            val at = o.optLong("at", 0)
            if (at <= now) continue
            val pi = pending(ctx, n, o.optString("title"), o.optString("body"), o.optString("id"))
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
                n++
            } catch (e: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, at, pi); n++
            }
        }
        prefs(ctx).edit().putInt("count", n).apply()
    }

    private fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val count = prefs(ctx).getInt("count", MAX)
        for (i in 0 until maxOf(count, 0)) {
            val pi = PendingIntent.getBroadcast(
                ctx, BASE_RC + i, Intent(ctx, ReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            am.cancel(pi); pi.cancel()
        }
        prefs(ctx).edit().putInt("count", 0).apply()
    }

    private fun pending(ctx: Context, idx: Int, title: String, body: String, id: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("title", title).putExtra("body", body).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, BASE_RC + idx, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
