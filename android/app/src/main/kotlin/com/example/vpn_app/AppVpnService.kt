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
import mobile.Mobile
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

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
        private const val READY_TIMEOUT_MS = 90_000L
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val WATCHDOG_FAIL_THRESHOLD = 10
        private const val TUNNEL_RESTART_DEBOUNCE_MS = 10_000L
        private const val OLCRTC_MAX_RETRIES = 3
        private val OLCRTC_RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L)
        private val mainHandler = Handler(Looper.getMainLooper())

        var isActive: Boolean = false
        var statusSink: EventChannel.EventSink? = null

        private fun notifyStatusChanged() {
            mainHandler.post {
                statusSink?.success(isActive)
            }
        }
    }

    private data class OlcrtcConfig(
        val carrier: String,
        val roomId: String,
        val clientId: String,
        val key: String
    )

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socks: Tun2SocksRunner? = null
    private var running = false
    @Volatile private var restartPending = false
    private var lastRestartTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var watchdogJob: ScheduledFuture<*>? = null
    private var watchdogFailures = 0
    @Volatile private var tunnelRestartPending = false
    private var lastTunnelRestartTime = 0L

    private val recoveryLogListener: (String) -> Unit = { line ->
        if (OlcrtcRecoveryPolicy.shouldRestartNative(line)) {
            val reason = OlcrtcRecoveryPolicy.restartReason(line)
            if (reason != null) {
                Log.w(TAG, "Critical native failure detected: $reason. Requesting restart.")
                requestFullTunnelRestart(reason)
            }
        }
    }

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
        OlcrtcLogManager.addListener(recoveryLogListener)
        Log.i(TAG, "VpnService created")
    }

    override fun onDestroy() {
        isActive = false
        notifyStatusChanged()
        requestTileUpdate()
        releaseWakeLock()
        stopTunnel(OlcrtcStopReason.VpnServiceStopped)
        OlcrtcLogManager.removeListener(recoveryLogListener)
        stopWatchdog()
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
        stopTunnel(OlcrtcStopReason.VpnRevoked)
        stopSelf()
        super.onRevoke()
    }

    private fun getOlcrtcConfig(): OlcrtcConfig {
        val prefs = getSharedPreferences("olcrtc_prefs", MODE_PRIVATE)
        var carrier = "jitsi"
        var roomId = ""
        var key = ""

        try {
            val flutterAssets = assets.list("flutter_assets")
            val envFile = flutterAssets?.firstOrNull { it == ".env" }

            if (envFile != null) {
                assets.open("flutter_assets/$envFile").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val eqIndex = trimmed.indexOf('=')
                            if (eqIndex > 0) {
                                val k = trimmed.substring(0, eqIndex).trim()
                                val v = trimmed.substring(eqIndex + 1).trim()
                                when (k) {
                                    "OLCRTC_CARRIER" -> carrier = v
                                    "JITSI_ROOM_ID" -> roomId = v
                                    "OLCRTC_KEY" -> key = v
                                }
                            }
                        }
                    }
                }
                Log.i(TAG, "Loaded .env from flutter_assets: $envFile")
            } else {
                Log.w(TAG, ".env file not found in flutter_assets")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read .env from assets", e)
        }

        val customRoomId = prefs.getString("custom_jitsi_room_id", null)
        if (!customRoomId.isNullOrEmpty()) {
            roomId = customRoomId
            Log.i(TAG, "Using custom roomId from SharedPreferences")
        }

        val clientId = prefs.getString("device_id", null) ?: run {
            val generated = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit { putString("device_id", generated) }
            Log.i(TAG, "Generated new device_id")
            generated
        }

        return OlcrtcConfig(carrier, roomId, clientId, key)
    }

    private fun connect() {
        if (vpnInterface != null || tun2socks != null) stopVpnRouting()

        val prepareIntent = prepare(this)
        if (prepareIntent != null) {
            Log.w(TAG, "VPN permission not granted. Aborting connect.")
            isActive = false
            notifyStatusChanged()
            requestTileUpdate()
            releaseWakeLock()
            stopSelf()
            return
        }

        isActive = true
        notifyStatusChanged()
        requestTileUpdate()
        acquireWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val builder = Builder()
            .setSession("DreamWalker").addAddress(VIRTUAL_ADDR, 24)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .addDnsServer("1.1.1.1").addDnsServer("1.0.0.1")
            .addDisallowedApplication(packageName).setMtu(1500).setBlocking(true)

        vpnInterface = builder.establish() ?: run {
            Log.e(TAG, "VPN interface creation failed")
            isActive = false
            notifyStatusChanged()
            requestTileUpdate()
            releaseWakeLock()
            stopSelf()
            return
        }

        Thread {
            try {
                val config = getOlcrtcConfig()
                if (config.roomId.isEmpty() || config.key.isEmpty()) {
                    Log.e(TAG, "Missing olcrtc config (roomId or key is empty). Aborting.")
                    mainHandler.post { disconnect() }
                    return@Thread
                }

                Mobile.setProtector { fd -> try { protect(fd.toInt()) } catch (_: Exception) { false } }
                Mobile.setLogWriter { line ->
                    line?.let {
                        Log.i("OlcrtcNative", it)
                        OlcrtcLogManager.log(it)
                    }
                }
                Mobile.setDebug(true)
                Mobile.setTransport("datachannel")
                Mobile.setSocksListenHost("127.0.0.1")

                Log.i(TAG, "Starting olcrtc...")
                Mobile.start(config.carrier, config.roomId, config.clientId, config.key, SOCKS_PORT.toLong(), "", "")
                Mobile.waitReady(READY_TIMEOUT_MS)

                if (Mobile.isRunning()) {
                    Log.i(TAG, "olcrtc started successfully")
                    startWatchdog()
                    mainHandler.post { startTun2Socks() }
                } else {
                    Log.e(TAG, "olcrtc failed to start")
                    mainHandler.post { disconnect() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting olcrtc", e)
                mainHandler.post { disconnect() }
            }
        }.start()
    }

    private fun startTun2Socks() {
        if (vpnInterface == null) return
        running = true
        tun2socks = Tun2SocksRunner(this)
        val tunStarted = tun2socks?.start(tunFd = vpnInterface!!, socksHost = SOCKS_SERVER, socksPort = SOCKS_PORT, mtu = MTU) ?: false
        if (!tunStarted) {
            Log.e(TAG, "tun2socks failed to start, rolling back VPN state")
            disconnect()
        } else {
            Log.i(TAG, "VPN + tun2socks started")
        }
    }

    fun disconnect() {
        isActive = false
        notifyStatusChanged()
        requestTileUpdate()
        releaseWakeLock()
        notifyStatusChanged()
        stopTunnel(OlcrtcStopReason.VpnServiceStopped)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    private fun stopTunnel(reason: OlcrtcStopReason) { stopVpnRouting(); stopOlcrtc(reason) }

    private fun stopVpnRouting() {
        running = false; tun2socks?.stop(); tun2socks = null
        try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "close interface", e) }
        vpnInterface = null
    }

    private fun stopOlcrtc(reason: OlcrtcStopReason) {
        if (!OlcrtcLifecyclePolicy.shouldStopNative(reason)) return
        stopWatchdog()
        try { if (Mobile.isRunning()) { Mobile.stop(); Log.i(TAG, "olcrtc stopped after $reason") } }
        catch (e: Exception) { Log.w(TAG, "stop olcrtc", e) }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID).setContentTitle("VPN активен").setContentText("Соединение установлено").setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(pi).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("VPN активен").setContentText("Соединение установлено").setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(pi).build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = watchdogExecutor.scheduleWithFixedDelay({
            if (tunnelRestartPending) return@scheduleWithFixedDelay
            if (Mobile.isRunning()) { watchdogFailures = 0 }
            else {
                watchdogFailures++
                if (watchdogFailures >= WATCHDOG_FAIL_THRESHOLD) { watchdogFailures = 0; requestFullTunnelRestart("watchdog_olcrtc_dead") }
            }
        }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopWatchdog() {
        watchdogJob?.let { if (!it.isDone) it.cancel(false) }; watchdogJob = null; watchdogFailures = 0
    }

    private fun requestFullTunnelRestart(reason: String) {
        val now = System.currentTimeMillis()
        if (tunnelRestartPending || (now - lastTunnelRestartTime) < TUNNEL_RESTART_DEBOUNCE_MS) return
        tunnelRestartPending = true; lastTunnelRestartTime = now
        Log.w(TAG, "Requesting full tunnel restart: $reason")
        stopWatchdog()

        Thread {
            var started = false
            try {
                try { Mobile.stop() } catch (_: Exception) {}
                Thread.sleep(1500)
                val config = getOlcrtcConfig()
                if (config.roomId.isEmpty() || config.key.isEmpty()) return@Thread

                for (attempt in 1..OLCRTC_MAX_RETRIES) {
                    try {
                        Mobile.setDebug(true); Mobile.setTransport("datachannel"); Mobile.setSocksListenHost("127.0.0.1")
                        Mobile.start(config.carrier, config.roomId, config.clientId, config.key, SOCKS_PORT.toLong(), "", "")
                        Mobile.waitReady(READY_TIMEOUT_MS)
                        if (Mobile.isRunning()) { started = true; break }
                    } catch (_: Exception) {
                        if (attempt < OLCRTC_MAX_RETRIES) { Thread.sleep(OLCRTC_RETRY_DELAYS_MS[attempt - 1]); try { Mobile.stop() } catch (_: Exception) {}; Thread.sleep(500) }
                    }
                }
            } finally { tunnelRestartPending = false }

            if (started) {
                startWatchdog()
                mainHandler.post { startService(Intent(this, AppVpnService::class.java).apply { action = "RESTART" }) }
            } else {
                mainHandler.post { disconnect() }
            }
        }.start()
    }
}