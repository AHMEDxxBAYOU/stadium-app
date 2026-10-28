package com.stadium.manager

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Optional: keeps the app alive in the background (with a small permanent notification) so the
 * live connection to the server stays open and booking notifications arrive right away.
 */
class KeepAliveService : Service() {

    companion object {
        private const val ID = 4242
        private const val PREFS = "stadium_keepalive"

        fun isWanted(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

        fun start(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", true).apply()
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { /* ignore */ }
        }

        fun stop(ctx: Context, forget: Boolean = false) {
            if (forget) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", false).apply()
            try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { /* ignore */ }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.createChannels(this)
        val n = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(Color.parseColor("#047857"))
            .setContentTitle("إدارة الملعب متصلة")
            .setContentText("تعمل في الخلفية لاستقبال إشعارات الحجوزات فور حدوثها")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifier.openAppIntent(this))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // The app was swiped away from recent apps: nothing left to keep alive.
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
