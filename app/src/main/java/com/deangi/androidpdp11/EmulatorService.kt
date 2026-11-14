package com.deangi.androidpdp11

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** User-visible foreground owner of the live emulator session. */
class EmulatorService : Service() {
    inner class LocalBinder : Binder() { val session: EmulatorSession get() = this@EmulatorService.session }
    private val binder = LocalBinder()
    private lateinit var session: EmulatorSession
    private var wakeLock: PowerManager.WakeLock? = null
    @Suppress("DEPRECATION") private var wifiLock: WifiManager.WifiLock? = null
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Emulator session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, EmulatorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AndroidEmulator8088 is running")
            .setContentText("Emulator and telnet session active")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidEmulatorPdp11:emulator").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = runCatching {
            val managerWifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            managerWifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AndroidEmulatorPdp11:telnet").apply { acquire() }
        }.getOrNull()
        session = EmulatorSession(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            session.stopping.value = true
            shutdownSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdownSession()
        super.onDestroy()
    }

    private fun shutdownSession() {
        if (stopped) return
        stopped = true
        if (::session.isInitialized) session.close()
        @Suppress("DEPRECATION")
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private companion object {
        const val CHANNEL = "emulator_session"
        const val NOTIFICATION_ID = 80
        const val ACTION_STOP = "com.deangi.androidpdp11.STOP"
    }
}
