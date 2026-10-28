package com.stadium.manager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_BOOKINGS = "bookings"
    const val CH_REMINDERS = "reminders"
    const val CH_FINANCE = "finance"
    const val CH_GENERAL = "general"
    const val CH_SERVICE = "service"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, desc: String, imp: Int) = NotificationChannel(id, name, imp).apply {
            description = desc
            enableLights(true); lightColor = Color.GREEN
            if (imp >= NotificationManager.IMPORTANCE_DEFAULT) enableVibration(true)
        }
        nm.createNotificationChannels(listOf(
            ch(CH_BOOKINGS, "الحجوزات", "حجز جديد، تعديل حجز، حذف حجز", NotificationManager.IMPORTANCE_HIGH),
            ch(CH_REMINDERS, "تذكير المواعيد", "تذكير قبل بداية موعد الحجز", NotificationManager.IMPORTANCE_HIGH),
            ch(CH_FINANCE, "الإيرادات والمصروفات", "إيراد أو مصروف جديد", NotificationManager.IMPORTANCE_DEFAULT),
            ch(CH_GENERAL, "تنبيهات عامة", "تنبيهات أخرى من المنظومة", NotificationManager.IMPORTANCE_HIGH),
            ch(CH_SERVICE, "التشغيل في الخلفية", "إشعار ثابت أثناء عمل التطبيق في الخلفية", NotificationManager.IMPORTANCE_MIN)
        ))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stadium_notif", Context.MODE_PRIVATE)
    fun markAsked(ctx: Context) = prefs(ctx).edit().putBoolean("asked", true).apply()

    /** "granted" | "default" (can still ask) | "denied" (must be enabled from phone settings) */
    fun permissionState(ctx: Context): String {
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) return if (enabled) "granted" else "denied"
            val asked = prefs(ctx).getBoolean("asked", false)
            if (!asked) return "default"
            val canAskAgain = ctx is Activity &&
                ActivityCompat.shouldShowRequestPermissionRationale(ctx, Manifest.permission.POST_NOTIFICATIONS)
            return if (canAskAgain) "default" else "denied"
        }
        return if (enabled) "granted" else "denied"
    }

    fun openAppIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, channel: String, title: String, body: String): Boolean {
        createChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val ch = when (channel) { CH_BOOKINGS, CH_REMINDERS, CH_FINANCE, CH_GENERAL -> channel; else -> CH_GENERAL }
        val n = NotificationCompat.Builder(ctx, ch)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(Color.parseColor("#047857"))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (ch == CH_FINANCE) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (ch == CH_REMINDERS) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
        return try { nm.notify(id, n); true } catch (e: SecurityException) { false }
    }
}
