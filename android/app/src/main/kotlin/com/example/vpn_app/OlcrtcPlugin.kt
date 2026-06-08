package com.example.vpn_app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import java.util.concurrent.Executors

class OlcrtcPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var logChannel: EventChannel
    private var logSink: EventChannel.EventSink? = null
    private var vpnEventSink: EventChannel.EventSink? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "OlcrtcPlugin"
        private const val SOCKS_PORT = 10808L
        private const val READY_TIMEOUT_MS = 60_000L
        private const val LIVENESS_INTERVAL_MS = 30_000L
        private const val LIVENESS_TIMEOUT_MS = 90_000L
        private const val LIVENESS_FAILURES = 3L
        private const val RECOVERY_COOLDOWN_MS = 60_000L
        lateinit var instance: OlcrtcPlugin
            private set
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

    private val logWriter = object : LogWriter {
        override fun writeLog(line: String?) {
            line?.let {
                emitLog(it)
                handleNativeLogLine(it)
            }
        }
    }

    private val socketProtector = object : SocketProtector {
        override fun protect(fd: Long): Boolean {
            return try {
                VpnServiceInstance.get()?.protect(fd.toInt()) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "protect() failed for fd=$fd", e)
                false
            }
        }
    }


    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        instance = this
        context = binding.applicationContext

        methodChannel = MethodChannel(binding.binaryMessenger, "olcrtc_channel")
        methodChannel.setMethodCallHandler(this)

        logChannel = EventChannel(binding.binaryMessenger, "olcrtc_logs")
        logChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) { logSink = events }
            override fun onCancel(arguments: Any?) { logSink = null }
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
        if (OlcrtcLifecyclePolicy.shouldStopNative(OlcrtcStopReason.FlutterEngineDetached)) {
            stopOlcrtc(OlcrtcStopReason.FlutterEngineDetached)
        } else {
            Log.i(TAG, "Flutter engine detached; keeping olcrtc running for VPN service")
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getDeviceId" -> {
                val androidId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                result.success("device-${androidId.take(8)}")
            }

            "start" -> {
                val carrier  = call.argument<String>("carrier")  ?: "jitsi"
                val roomId   = call.argument<String>("roomId")   ?: ""
                val clientId = call.argument<String>("clientId") ?: ""
                val key      = call.argument<String>("key")      ?: ""
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
    }

    private fun stopOlcrtc(reason: OlcrtcStopReason) {
        if (!OlcrtcLifecyclePolicy.shouldStopNative(reason)) {
            Log.i(TAG, "Skipping olcrtc stop for $reason")
            return
        }

        currentStartConfig = null
        resetRecoveryState()
        Log.i(TAG, "Stopping olcrtc: $reason")
        Mobile.stop()
        Log.i(TAG, "olcrtc stopped")
    }

    private fun handleNativeLogLine(line: String) {
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
        try {
            if (currentStartConfig != config) {
                Log.i(TAG, "Canceled olcrtc recovery because tunnel config changed")
                return
            }

            Log.w(TAG, "Restarting olcrtc after native reconnect failure: $reason")
            emitLog("olcrtc reconnect failed; restarting native tunnel ($reason)")
            Mobile.stop()
            Thread.sleep(1000)

            if (currentStartConfig != config) {
                Log.i(TAG, "Canceled olcrtc recovery because tunnel was stopped")
                return
            }

            startOlcrtc(config)
            Log.i(TAG, "olcrtc restarted after native reconnect failure")
            emitLog("olcrtc restarted after reconnect failure")
        } catch (e: Exception) {
            Log.e(TAG, "olcrtc recovery restart failed", e)
            val message = e.message ?: e.javaClass.simpleName
            emitLog("olcrtc restart after reconnect failure failed: $message")
        } finally {
            finishRecoverySlot()
        }
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
}