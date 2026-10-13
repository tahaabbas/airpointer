package com.airpointer.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that runs while a connection exists. It keeps the app process (and its socket)
 * alive when the phone screen turns off or you switch apps, and holds a Wi-Fi lock so Android's
 * Wi-Fi power saving doesn't stall or drop the link. Only the user's "Disconnect" stops it.
 */
class ConnectionService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val listener: (Link.State, String?) -> Unit = { state, _ ->
        if (state == Link.State.DISCONNECTED) stopSelf() else notifyState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while AirPointer is connected to a computer"
                    setShowBadge(false)
                },
            )
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                   else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "AirPointer:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirPointer:link").apply {
            setReferenceCounted(false)
            acquire()
        }
        Link.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first (required after startForegroundService).
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        if (intent?.action == ACTION_DISCONNECT) Link.disconnect()
        if (Link.state == Link.State.DISCONNECTED) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Link.removeListener(listener)
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun notifyState() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT), flags,
        )
        val text = when (Link.state) {
            Link.State.RECONNECTING -> "Reconnecting to ${Link.name}…"
            else -> "Connected to ${Link.name}"
        }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
                else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_pointer)
            .setContentTitle("AirPointer")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.airpointer.remote.DISCONNECT"

        fun start(context: Context) {
            val i = Intent(context, ConnectionService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
