package com.example.vpn_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

class AppVpnService : VpnService() {
    companion object {
        private const val TAG = "AppVpnService"
        private const val VIRTUAL_ADDR = "10.0.0.2"
        private const val NOTIF_CHANNEL_ID = "vpn_channel"
        private const val NOTIF_ID = 1
        private const val SOCKS_SERVER = "127.0.0.1"
        private const val SOCKS_PORT = 10808
        private const val MTU = 1500
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socks: Tun2SocksRunner? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        VpnServiceInstance.set(this)
        createNotificationChannel()
        Log.i(TAG, "VpnService created")
    }

    override fun onDestroy() {
        VpnServiceInstance.clear()
        super.onDestroy()
        Log.i(TAG, "VpnService destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "CONNECT"    -> connect()
            "DISCONNECT" -> disconnect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (vpnInterface != null) disconnect()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
            .setMtu(1500)
            .setBlocking(true)

        vpnInterface = builder.establish() ?: run {
            Log.e(TAG, "VPN interface creation failed")
            stopSelf()
            return
        }

        running = true

        tun2socks = Tun2SocksRunner(this)
        tun2socks?.start(
            tunFd = vpnInterface!!,
            socksHost = SOCKS_SERVER,
            socksPort = SOCKS_PORT,
            mtu = MTU
        )

        Log.i(TAG, "VPN + tun2socks started")
    }

    fun disconnect() {
        running = false

        tun2socks?.stop()
        tun2socks = null


        try {
            vpnInterface?.close()
            Log.i(TAG, "TUN interface closed")
        } catch (e: Exception) {
            Log.w(TAG, "close interface", e)
        }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("VPN подключен")
                .setContentText("Трафик защищен")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("VPN подключен")
                .setContentText("Трафик защищен")
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