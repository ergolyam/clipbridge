package io.ergolyam.clipbridge.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.ergolyam.clipbridge.R
import io.ergolyam.clipbridge.ui.MainActivity

object Notifications {

    const val CHANNEL_ID = "clipbridge_channel"
    const val NOTIF_ID = 1001

    fun initChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.notif_channel_desc)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun contentIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, 0, intent, flags)
    }

    fun notifConnecting(ctx: Context, endpoint: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notif_title_connecting))
            .setContentText(endpoint)
            .setOngoing(true)
            .setContentIntent(contentIntent(ctx))
            .build()

    fun notifConnected(ctx: Context, endpoint: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notif_title_connected))
            .setContentText(endpoint)
            .setOngoing(true)
            .setContentIntent(contentIntent(ctx))
            .build()

    fun notifWaiting(ctx: Context, endpoint: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notif_title_waiting))
            .setContentText(endpoint)
            .setOngoing(true)
            .setContentIntent(contentIntent(ctx))
            .build()

    fun notifDisconnected(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notif_title_disconnected))
            .setOngoing(true)
            .setContentIntent(contentIntent(ctx))
            .build()
}

