package com.example.vpn_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import io.flutter.plugin.common.EventChannel

class AppVpnService : VpnService() {
    companion object {
        private const val TAG = "AppVpnService"
        private const val VIRTUAL_ADDR = "10.0.0.2"
        private const val NOTIF_CHANNEL_ID = "vpn_channel"
        private const val NOTIF_ID = 1
        private const val SOCKS_SERVER = "127.0.0.1"
        private const val SOCKS_PORT = 10808
        private const val MTU = 1500
        private const val RESTART_DEBOUNCE_MS = 5_000L
        private const val WAKE_LOCK_TAG = "DreamWalker:VpnWakeLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        private val mainHandler = Handler(Looper.getMainLooper())
        var isActive: Boolean = false
        var statusSink: EventChannel.EventSink? = null

        private fun notifyStatusChanged() {
            mainHandler.post {
                statusSink?.success(isActive)
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socks: Tun2SocksRunner? = null
    private var running = false
    @Volatile private var restartPending = false
    private var lastRestartTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private fun requestTileUpdate() {
        try {
            TileService.requestListeningState(this, ComponentName(this, TileService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request tile update", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        VpnServiceInstance.set(this)
        createNotificationChannel()
        Log.i(TAG, "VpnService created")
    }

    override fun onDestroy() {
        isActive = false
        notifyStatusChanged()
        requestTileUpdate()
        releaseWakeLock()
        stopTunnel()
        VpnServiceInstance.clear()
        super.onDestroy()
        Log.i(TAG, "VpnService destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            "CONNECT" -> { connect(); START_STICKY }
            "DISCONNECT" -> { disconnect(); START_NOT_STICKY }
            "RESTART" -> {
                val now = System.currentTimeMillis()
                if (restartPending || (now - lastRestartTime) < RESTART_DEBOUNCE_MS) return START_STICKY
                restartPending = true
                lastRestartTime = now
                Log.i(TAG, "Received RESTART command")
                stopVpnRouting()
                mainHandler.postDelayed({ restartPending = false; connect() }, 1000)
                START_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        isActive = false
        notifyStatusChanged()
        requestTileUpdate()
        releaseWakeLock()
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    private fun connect() {
        if (vpnInterface != null || tun2socks != null) {
            stopVpnRouting()
        }
        acquireWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val builder = Builder()
            .setSession("DreamWalker")
            .addAddress(VIRTUAL_ADDR, 24)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
            .addDisallowedApplication(packageName)
            .setMtu(MTU)
            .setBlocking(true)

        vpnInterface = builder.establish() ?: run {
            Log.e(TAG, "VPN interface creation failed")
            releaseWakeLock()
            stopSelf()
            return
        }

        running = true

        isActive = true
        notifyStatusChanged()
        requestTileUpdate()
        Log.i(TAG, "VPN interface established, waiting for olcrtc to start SOCKS5...")
    }
    fun startTun2SocksIfNeeded() {
        if (vpnInterface == null) {
            Log.w(TAG, "Cannot start tun2socks: vpnInterface is null")
            return
        }
        if (tun2socks != null) {
            Log.i(TAG, "tun2socks already running")
            return
        }
        Log.i(TAG, "Starting tun2socks now (SOCKS5 is ready)...")
        tun2socks = Tun2SocksRunner(this)
        tun2socks?.start(
            tunFd = vpnInterface!!,
            socksHost = SOCKS_SERVER,
            socksPort = SOCKS_PORT,
            mtu = MTU
        )
    }

    fun disconnect() {
        isActive = false
        notifyStatusChanged()
        requestTileUpdate()
        releaseWakeLock()
        Thread {
            try {
                stopTunnel()
            } catch (e: Exception) {
                Log.w(TAG, "Error in stopTunnel", e)
            }
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "VPN stopped")
            }
        }.apply {
            name = "vpn-disconnect"
            isDaemon = true
            start()
        }
    }

    private fun stopTunnel() {
        stopVpnRouting()
    }

    private fun stopVpnRouting() {
        running = false
        tun2socks?.stop()
        tun2socks = null
        try {
            vpnInterface?.close()
            if (vpnInterface != null) {
                Log.i(TAG, "TUN interface closed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "close interface", e)
        }
        vpnInterface = null
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.i(TAG, "WakeLock acquired (timeout=24h)")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("VPN активен")
                .setContentText("Соединение установлено")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("VPN активен")
                .setContentText("Соединение установлено")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}