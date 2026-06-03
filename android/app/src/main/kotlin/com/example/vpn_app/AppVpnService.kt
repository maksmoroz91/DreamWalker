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
import java.io.FileInputStream
import java.io.FileOutputStream

class AppVpnService : VpnService() {
    companion object {
        private const val TAG = "AppVpnService"
        private const val VIRTUAL_ADDR = "10.0.0.2"
        private const val NOTIF_CHANNEL_ID = "vpn_channel"
        private const val NOTIF_ID = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReaderThread: Thread? = null
    private var running = false

    // Канал отправки пакетов из TUN в olcrtc — будет заполнен из TunBridge
    var onPacket: ((ByteArray) -> Unit)? = null

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
            .setSession("VPN")
            .addAddress(VIRTUAL_ADDR, 24)
            .addRoute("0.0.0.0", 0)          // весь IPv4 трафик через TUN
            .addRoute("::", 0)               // весь IPv6
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .addDisallowedApplication(packageName)  // само приложение не через TUN
            .setMtu(1500)
            .setBlocking(false)

        vpnInterface = builder.establish() ?: run {
            Log.e(TAG, "VPN interface creation failed")
            stopSelf()
            return
        }

        running = true

        // Читаем пакеты из TUN и передаём в olcrtc через SOCKS5
        tunReaderThread = Thread {
            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(32768)
            while (running) {
                val len = try { input.read(buffer) } catch (e: Exception) { break }
                if (len > 0) {
                    onPacket?.invoke(buffer.copyOf(len))
                }
            }
            Log.i(TAG, "TUN reader thread stopped")
        }.apply {
            name = "tun-reader"
            isDaemon = true
            start()
        }

        Log.i(TAG, "VPN interface up, addr=$VIRTUAL_ADDR")
    }

    fun disconnect() {
        running = false
        tunReaderThread?.interrupt()
        tunReaderThread = null
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    fun writePacket(packet: ByteArray) {
        vpnInterface?.let {
            try {
                FileOutputStream(it.fileDescriptor).write(packet)
            } catch (e: Exception) {
                Log.e(TAG, "writePacket error", e)
            }
        }
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
                .setContentText("Туннель работает")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("VPN активен")
                .setContentText("Туннель работает")
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
