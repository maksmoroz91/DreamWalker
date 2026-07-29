package com.example.vpn_app

import android.content.Context
import android.util.Log
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

object OlcrtcNativeRunner {
    private const val TAG = "OlcrtcNativeRunner"
    private const val SOCKS_PORT = 10808L
    private const val READY_TIMEOUT_MS = 90_000L
    private const val WATCHDOG_INTERVAL_MS = 30_000L
    private const val WATCHDOG_FAIL_THRESHOLD = 10
    private const val TUNNEL_RESTART_DEBOUNCE_MS = 10_000L
    private const val OLCRTC_MAX_RETRIES = 3
    private val OLCRTC_RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private lateinit var appContext: Context
    @Volatile private var initialized = false
    @Volatile var logListener: ((String) -> Unit)? = null

    private data class OlcrtcStartConfig(
        val carrier: String,
        val roomId: String,
        val clientId: String,
        val key: String,
    )

    @Volatile private var currentStartConfig: OlcrtcStartConfig? = null
    @Volatile private var tunnelRestartPending = false
    private var lastTunnelRestartTime = 0L
    private var watchdogJob: ScheduledFuture<*>? = null
    private var watchdogFailures = 0

    private val logWriter = LogWriter { line ->
        line?.let {
            emitLog(it)
            handleNativeLogLine(it)
        }
    }

    private val socketProtector = SocketProtector { fd ->
        try {
            VpnServiceInstance.get()?.protect(fd.toInt()) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "protect() failed for fd=$fd", e)
            false
        }
    }

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        Mobile.setLogWriter(logWriter)
        Mobile.setProtector(socketProtector)
        initialized = true
        Log.i(TAG, "OlcrtcNativeRunner initialized")
    }

    fun isRunning(): Boolean = Mobile.isRunning()

    fun start(
        context: Context,
        carrier: String,
        roomId: String,
        clientId: String,
        key: String,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        ensureInitialized(context)
        val config = OlcrtcStartConfig(carrier, roomId, clientId, key)
        currentStartConfig = config
        bgExecutor.submit {
            try {
                stopNativeQuietly()
                Thread.sleep(1000)
                startOlcrtc(config)
                mainHandler.post { onResult(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "olcrtc start failed", e)
                currentStartConfig = null
                mainHandler.post { onResult(false, e.message) }
            }
        }
    }

    fun stop(reason: OlcrtcStopReason, onResult: (() -> Unit)? = null) {
        currentStartConfig = null
        bgExecutor.submit {
            try {
                stopOlcrtc(reason)
            } catch (e: Exception) {
                Log.e(TAG, "stop error", e)
            }
            if (onResult != null) mainHandler.post(onResult)
        }
    }

    private fun startOlcrtc(config: OlcrtcStartConfig) {
        Mobile.setDebug(true)
        Mobile.setTransport("datachannel")
        Mobile.setSocksListenHost("127.0.0.1")
        Mobile.start(
            config.carrier,
            config.roomId,
            config.clientId,
            config.key,
            SOCKS_PORT,
            "",
            ""
        )
        Log.i(TAG, "olcrtc start() called, waiting for ready...")
        Mobile.waitReady(READY_TIMEOUT_MS)

        if (Mobile.isRunning()) {
            Log.i(TAG, "olcrtc is running, triggering tun2socks start")
            VpnServiceInstance.get()?.startTun2SocksIfNeeded()
            startWatchdog()
        } else {
            Log.w(TAG, "Mobile.isRunning() == false after start, skipping watchdog and tun2socks")
        }
    }

    private fun stopOlcrtc(reason: OlcrtcStopReason) {
        if (!OlcrtcLifecyclePolicy.shouldStopNative(reason)) {
            Log.i(TAG, "Skipping olcrtc stop for $reason")
            return
        }
        stopWatchdog()
        Log.i(TAG, "Stopping olcrtc: $reason")
        Mobile.stop()
        Log.i(TAG, "olcrtc stopped")
    }

    private fun stopNativeQuietly() {
        try { Mobile.stop() } catch (_: Exception) {}
    }

    private fun requestFullTunnelRestart(reason: String) {
        val now = System.currentTimeMillis()
        if (tunnelRestartPending || (now - lastTunnelRestartTime) < TUNNEL_RESTART_DEBOUNCE_MS) {
            Log.i(TAG, "Full tunnel restart debounced for: $reason")
            return
        }
        tunnelRestartPending = true
        lastTunnelRestartTime = now
        Log.w(TAG, "Requesting full tunnel restart: $reason")
        emitLog("Restarting tunnel: $reason")
        stopWatchdog()

        val config = currentStartConfig
        if (config == null) {
            Log.w(TAG, "No start config saved, cannot restart olcrtc")
            tunnelRestartPending = false
            return
        }

        bgExecutor.submit {
            var started = false
            try {
                stopNativeQuietly()
                Thread.sleep(1500)
                for (attempt in 1..OLCRTC_MAX_RETRIES) {
                    try {
                        Log.i(TAG, "olcrtc restart attempt $attempt/$OLCRTC_MAX_RETRIES after: $reason")
                        emitLog("olcrtc restart attempt $attempt/$OLCRTC_MAX_RETRIES...")
                        startOlcrtc(config)
                        Log.i(TAG, "olcrtc restarted successfully (attempt $attempt) after: $reason")
                        started = true
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "olcrtc restart attempt $attempt failed: ${e.message}")
                        emitLog("olcrtc restart attempt $attempt failed: ${e.message}")
                        if (attempt < OLCRTC_MAX_RETRIES) {
                            val delay = OLCRTC_RETRY_DELAYS_MS[attempt - 1]
                            Log.i(TAG, "Retrying in ${delay}ms...")
                            Thread.sleep(delay)
                            stopNativeQuietly()
                            Thread.sleep(500)
                        } else {
                            Log.e(TAG, "olcrtc restart exhausted all attempts after: $reason")
                            emitLog("olcrtc restart failed after $OLCRTC_MAX_RETRIES attempts")
                        }
                    }
                }
            } finally {
                tunnelRestartPending = false
            }

            if (started) {
                mainHandler.post {
                    appContext.startService(
                        android.content.Intent(appContext, AppVpnService::class.java).apply { action = "RESTART" }
                    )
                }
            } else {
                Log.w(TAG, "Skipping VPN RESTART -- olcrtc did not start")
                emitLog("VPN не перезапущен: olcrtc не удалось поднять")
            }
        }
    }

    private fun handleNativeLogLine(line: String) {
        val reason = OlcrtcRecoveryPolicy.restartReason(line) ?: return
        Log.w(TAG, "Critical native failure detected: $reason. Requesting full tunnel restart.")
        requestFullTunnelRestart(reason)
    }

    private fun emitLog(line: String) {
        OlcrtcLogManager.log(line)
        mainHandler.post { logListener?.invoke(line) }
    }

    private fun startWatchdog() {
        stopWatchdog()
        Log.i(TAG, "Watchdog started (interval=${WATCHDOG_INTERVAL_MS}ms, threshold=$WATCHDOG_FAIL_THRESHOLD)")
        watchdogJob = watchdogExecutor.scheduleWithFixedDelay({
            if (tunnelRestartPending) {
                Log.d(TAG, "Watchdog skip: restart already pending")
                return@scheduleWithFixedDelay
            }
            if (Mobile.isRunning()) {
                if (watchdogFailures > 0) {
                    Log.i(TAG, "Watchdog: olcrtc recovered (failures reset)")
                }
                watchdogFailures = 0
            } else {
                watchdogFailures++
                Log.w(TAG, "Watchdog: olcrtc not running ($watchdogFailures/$WATCHDOG_FAIL_THRESHOLD)")
                if (watchdogFailures >= WATCHDOG_FAIL_THRESHOLD) {
                    watchdogFailures = 0
                    requestFullTunnelRestart("watchdog_olcrtc_dead")
                }
            }
        }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopWatchdog() {
        watchdogJob?.let {
            if (!it.isDone) {
                it.cancel(false)
                Log.i(TAG, "Watchdog stopped")
            }
        }
        watchdogJob = null
        watchdogFailures = 0
    }
}