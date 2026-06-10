package com.example.vpn_app

import android.content.Context
import androidx.core.content.edit
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
class OlcrtcPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var logChannel: EventChannel
    private var logSink: EventChannel.EventSink? = null
    private var vpnEventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val keepaliveExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    companion object {
        private const val TAG = "OlcrtcPlugin"
        private const val SOCKS_PORT = 10808L
        private const val READY_TIMEOUT_MS = 60_000L
        private const val LIVENESS_INTERVAL_MS = 30_000L
        private const val LIVENESS_TIMEOUT_MS = 90_000L
        private const val LIVENESS_FAILURES = 3L
        private const val RECOVERY_COOLDOWN_MS = 40_000L
        private const val SESSION_READY_TIMEOUT_MS = 30_000L
        private const val STOP_GRACE_PERIOD_MS = 20_000L
        private const val MAX_RECOVERY_RETRIES = 3

        private const val KEEPALIVE_INTERVAL_MS = 25_000L
        private const val KEEPALIVE_URL = "https://cp.cloudflare.com/"
        private const val KEEPALIVE_TIMEOUT_MS = 10_000L
        private const val KEEPALIVE_FAIL_THRESHOLD = 2

    }

    private data class OlcrtcStartConfig(
        val carrier: String,
        val roomId: String,
        val clientId: String,
        val key: String,
    )

    @Volatile
    private var currentStartConfig: OlcrtcStartConfig? = null
    private val recoveryLock = Any()
    private var recoveryInProgress = false
    private var lastRecoveryAtMs = 0L
    private val sessionReadyLatch = AtomicReference<CountDownLatch?>(null)
    private val nativeRecoveredOnItsOwn = AtomicBoolean(false)

    @Volatile
    private var keepaliveJob: ScheduledFuture<*>? = null
    private var keepaliveConsecutiveFailures = 0

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

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        methodChannel = MethodChannel(binding.binaryMessenger, "olcrtc_channel")
        methodChannel.setMethodCallHandler(this)

        logChannel = EventChannel(binding.binaryMessenger, "olcrtc_logs")
        logChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                logSink = events
            }

            override fun onCancel(arguments: Any?) {
                logSink = null
            }
        })

        EventChannel(binding.binaryMessenger, "vpn_events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    vpnEventSink = events
                    Log.d(TAG, "vpn_events listener attached")
                }

                override fun onCancel(arguments: Any?) {
                    vpnEventSink = null
                }
            })

        Mobile.setLogWriter(logWriter)
        Mobile.setProtector(socketProtector)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        logSink = null
        vpnEventSink = null
        stopKeepalive()
        if (OlcrtcLifecyclePolicy.shouldStopNative(OlcrtcStopReason.FlutterEngineDetached)) {
            stopOlcrtc(OlcrtcStopReason.FlutterEngineDetached)
        } else {
            Log.i(TAG, "Flutter engine detached; keeping olcrtc running for VPN service")
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getDeviceId" -> {
                val prefs = context.getSharedPreferences("olcrtc_prefs", Context.MODE_PRIVATE)
                val id = prefs.getString("device_id", null) ?: run {
                    val generated = java.util.UUID.randomUUID().toString().replace("-", "")
                    prefs.edit { putString("device_id", generated) }
                    generated
                }
                result.success("device-${id.take(8)}")
            }

            "start" -> {
                val carrier = call.argument<String>("carrier") ?: "jitsi"
                val roomId = call.argument<String>("roomId") ?: ""
                val clientId = call.argument<String>("clientId") ?: ""
                val key = call.argument<String>("key") ?: ""
                val config = OlcrtcStartConfig(carrier, roomId, clientId, key)
                currentStartConfig = null
                resetRecoveryState()

                bgExecutor.submit {
                    var retries = 0
                    val maxRetries = 3

                    while (retries < maxRetries) {
                        try {
                            Log.i(TAG, "olcrtc start attempt ${retries + 1}/$maxRetries")

                            stopNativeQuietly()
                            Thread.sleep(1000)

                            startOlcrtc(config)

                            currentStartConfig = config
                            Log.i(TAG, "olcrtc started successfully")
                            mainHandler.post { result.success(true) }
                            return@submit

                        } catch (e: Exception) {
                            Log.e(TAG, "olcrtc start failed (attempt ${retries + 1})", e)
                            retries++

                            if (retries < maxRetries) {
                                Log.i(TAG, "Retrying in 3 seconds...")
                                Thread.sleep(3000)
                            } else {
                                Log.e(TAG, "Max retries reached")
                                currentStartConfig = null
                                resetRecoveryState()
                                mainHandler.post { result.error("START_FAILED", e.message, null) }
                            }
                        }
                    }
                }
            }

            "stop" -> {
                currentStartConfig = null
                resetRecoveryState()
                bgExecutor.submit {
                    try {
                        stopOlcrtc(OlcrtcStopReason.UserRequest)
                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        Log.e(TAG, "stop error", e)
                        mainHandler.post { result.error("STOP_FAILED", e.message, null) }
                    }
                }
            }

            "isRunning" -> result.success(Mobile.isRunning())

            else -> result.notImplemented()
        }
    }

    private fun startOlcrtc(config: OlcrtcStartConfig) {
        Mobile.setDebug(true)
        Mobile.setTransport("datachannel")
        Mobile.setSocksListenHost("127.0.0.1")
        Mobile.setLivenessOptions(
            LIVENESS_INTERVAL_MS,
            LIVENESS_TIMEOUT_MS,
            LIVENESS_FAILURES
        )
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
            startKeepalive()
        } else {
            Log.w(TAG, "Mobile.isRunning() == false after start, skipping keepalive")
        }
    }

    private fun stopOlcrtc(reason: OlcrtcStopReason) {
        if (!OlcrtcLifecyclePolicy.shouldStopNative(reason)) {
            Log.i(TAG, "Skipping olcrtc stop for $reason")
            return
        }

        stopKeepalive()

        currentStartConfig = null
        resetRecoveryState()
        Log.i(TAG, "Stopping olcrtc: $reason")
        Mobile.stop()
        Log.i(TAG, "olcrtc stopped")
    }

    private fun handleNativeLogLine(line: String) {
        if (line.contains("jitsi: reconnected")) {
            Log.i(TAG, ">>> Native olcrtc reconnected on its own: $line")
            nativeRecoveredOnItsOwn.set(true)
            sessionReadyLatch.get()?.countDown()
        }

        if (line.contains("session opened")) {
            sessionReadyLatch.get()?.countDown()
        }

        if (!OlcrtcRecoveryPolicy.shouldRestartNative(line)) return
        val reason = OlcrtcRecoveryPolicy.restartReason(line) ?: return
        val config = currentStartConfig ?: run {
            Log.w(TAG, "Ignoring olcrtc recovery signal without start config: $reason")
            return
        }
        if (!claimRecoverySlot(reason)) return

        bgExecutor.submit {
            if (!isNativeRunningForRecovery()) {
                finishRecoverySlot()
                return@submit
            }
            recoverOlcrtc(config, reason)
        }
    }

    private fun claimRecoverySlot(reason: String): Boolean {
        synchronized(recoveryLock) {
            val now = System.currentTimeMillis()
            if (recoveryInProgress) {
                Log.i(TAG, "Skipping olcrtc recovery while another restart is running: $reason")
                return false
            }
            if (now - lastRecoveryAtMs < RECOVERY_COOLDOWN_MS) {
                Log.i(TAG, "Skipping olcrtc recovery during cooldown: $reason")
                return false
            }

            recoveryInProgress = true
            lastRecoveryAtMs = now
            return true
        }
    }

    private fun isNativeRunningForRecovery(): Boolean {
        return try {
            Mobile.isRunning()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check olcrtc state for recovery", e)
            false
        }
    }

    private fun recoverOlcrtc(config: OlcrtcStartConfig, reason: String) {
        var retryCount = 0

        while (retryCount < MAX_RECOVERY_RETRIES) {
            try {
                if (currentStartConfig != config) {
                    Log.i(TAG, "Canceled olcrtc recovery because tunnel config changed")
                    return
                }

                nativeRecoveredOnItsOwn.set(false)
                stopKeepalive()

                Log.w(
                    TAG,
                    "Restarting olcrtc after native reconnect failure: $reason (attempt ${retryCount + 1}/$MAX_RECOVERY_RETRIES)"
                )
                emitLog("olcrtc reconnect failed; restarting native tunnel ($reason)")

                Log.i(TAG, "Waiting for native reconnect cycle to finish before stop...")
                Thread.sleep(STOP_GRACE_PERIOD_MS)

                if (currentStartConfig != config) {
                    Log.i(TAG, "Canceled olcrtc recovery: config changed during grace period")
                    return
                }

                if (nativeRecoveredOnItsOwn.getAndSet(false)) {
                    Log.i(TAG, ">>> Native olcrtc recovered on its own during grace period — skipping restart")
                    emitLog("olcrtc self-recovered, restart canceled")
                    sessionReadyLatch.set(null)
                    startKeepalive()
                    finishRecoverySlot()
                    return
                }

                Log.i(TAG, "Native did not recover on its own, proceeding with Mobile.stop()")
                Mobile.stop()
                Log.i(TAG, "Mobile.stop() completed")
                Thread.sleep(1000)

                if (currentStartConfig != config) {
                    Log.i(TAG, "Canceled olcrtc recovery because tunnel was stopped")
                    return
                }

                val latch = CountDownLatch(1)
                sessionReadyLatch.set(latch)

                startOlcrtc(config)

                val serverReady = latch.await(SESSION_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (serverReady) {
                    Log.i(TAG, "olcrtc restarted, server session confirmed")
                    emitLog("olcrtc restarted after reconnect failure (server confirmed)")
                    sessionReadyLatch.set(null)
                    startKeepalive()
                    finishRecoverySlot()
                    return
                } else {
                    Log.w(TAG, "olcrtc restarted, server session NOT confirmed within timeout")
                    emitLog("olcrtc restarted after reconnect failure (server confirmation timeout)")

                    if (retryCount < MAX_RECOVERY_RETRIES - 1) {
                        Log.i(TAG, "Retrying recovery in 3 seconds...")
                        Thread.sleep(3000)
                        retryCount++
                        continue
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "olcrtc recovery restart failed (attempt ${retryCount + 1})", e)
                val message = e.message ?: e.javaClass.simpleName
                emitLog("olcrtc restart after reconnect failure failed: $message")

                if (retryCount < MAX_RECOVERY_RETRIES - 1) {
                    Log.i(TAG, "Retrying recovery in 3 seconds...")
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    retryCount++
                    continue
                }
            }

            break
        }

        Log.e(TAG, "All $MAX_RECOVERY_RETRIES recovery attempts failed")
        emitLog("olcrtc recovery failed after $MAX_RECOVERY_RETRIES attempts")
        sessionReadyLatch.set(null)
        finishRecoverySlot()
    }

    private fun stopNativeQuietly() {
        try {
            Mobile.stop()
        } catch (_: Exception) {
        }
    }

    private fun resetRecoveryState() {
        synchronized(recoveryLock) {
            recoveryInProgress = false
            lastRecoveryAtMs = 0L
        }
    }

    private fun finishRecoverySlot() {
        synchronized(recoveryLock) {
            recoveryInProgress = false
        }
    }

    private fun emitLog(line: String) {
        mainHandler.post { logSink?.success(line) }
    }

    private fun startKeepalive() {
        stopKeepalive()

        Log.i(TAG, ">>> Keepalive starting (interval=${KEEPALIVE_INTERVAL_MS}ms)")

        keepaliveJob = keepaliveExecutor.scheduleWithFixedDelay({
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT.toInt()))
                val url = URL(KEEPALIVE_URL)
                val conn = url.openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = KEEPALIVE_TIMEOUT_MS.toInt()
                conn.readTimeout = KEEPALIVE_TIMEOUT_MS.toInt()
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = false

                val code = conn.responseCode
                conn.disconnect()
                keepaliveConsecutiveFailures = 0
                Log.d(TAG, "keepalive ok: HTTP $code")
            } catch (e: Exception) {
                keepaliveConsecutiveFailures++
                Log.w(TAG, "keepalive failed (${keepaliveConsecutiveFailures}/${KEEPALIVE_FAIL_THRESHOLD}): ${e.javaClass.simpleName}: ${e.message}")
                if (keepaliveConsecutiveFailures >= KEEPALIVE_FAIL_THRESHOLD) {
                    Log.w(TAG, ">>> Keepalive: $KEEPALIVE_FAIL_THRESHOLD consecutive failures — triggering recovery")
                    keepaliveConsecutiveFailures = 0
                    val config = currentStartConfig
                    if (config != null && claimRecoverySlot("keepalive_timeout")) {
                        bgExecutor.submit {
                            if (!isNativeRunningForRecovery()) {
                                finishRecoverySlot()
                                return@submit
                            }
                            recoverOlcrtc(config, "keepalive_timeout")
                        }
                    } else if (config == null) {
                        Log.w(TAG, ">>> Keepalive recovery skipped: no active config")
                    }
                }
            }
        }, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopKeepalive() {
        keepaliveJob?.let { job ->
            if (!job.isDone) {
                job.cancel(false)
                Log.i(TAG, ">>> Keepalive stopped")
            }
        }
        keepaliveJob = null
    }
}