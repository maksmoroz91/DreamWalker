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
        lateinit var instance: OlcrtcPlugin
            private set
    }

    private val logWriter = object : LogWriter {
        override fun writeLog(line: String?) {
            line?.let {
                mainHandler.post { logSink?.success(it) }
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

                bgExecutor.submit {
                    var retries = 0
                    val maxRetries = 3

                    while (retries < maxRetries) {
                        try {
                            Log.i(TAG, "olcrtc start attempt ${retries + 1}/$maxRetries")

                            try { Mobile.stop() } catch (e: Exception) {}
                            Thread.sleep(1000)

                            Mobile.setDebug(true)
                            Mobile.setTransport("datachannel")
                            Mobile.setSocksListenHost("127.0.0.1")
                            Mobile.setLivenessOptions(
                                LIVENESS_INTERVAL_MS,
                                LIVENESS_TIMEOUT_MS,
                                LIVENESS_FAILURES
                            )
                            Mobile.start(carrier, roomId, clientId, key, SOCKS_PORT, "", "")

                            Log.i(TAG, "olcrtc start() called, waiting for ready...")
                            Mobile.waitReady(READY_TIMEOUT_MS)

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
                                mainHandler.post { result.error("START_FAILED", e.message, null) }
                            }
                        }
                    }
                }
            }

            "stop" -> {
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

    private fun stopOlcrtc(reason: OlcrtcStopReason) {
        if (!OlcrtcLifecyclePolicy.shouldStopNative(reason)) {
            Log.i(TAG, "Skipping olcrtc stop for $reason")
            return
        }

        Log.i(TAG, "Stopping olcrtc: $reason")
        Mobile.stop()
        Log.i(TAG, "olcrtc stopped")
    }
}