package com.example.vpn_app

import android.content.Context
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import mobile.Mobile
import mobile.LogWriter
import mobile.SocketProtector

class OlcrtcPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var logChannel: EventChannel
    private var logSink: EventChannel.EventSink? = null

    companion object {
        private const val TAG = "OlcrtcPlugin"
    }

    private val logWriter = object : LogWriter {
        override fun write(line: String?) {
            line?.let {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    logSink?.success(it)
                }
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

        Mobile.setLogWriter(logWriter)
        Mobile.setProtector(socketProtector)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        logSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {

            // Уникальный ID устройства на основе ANDROID_ID
            "getDeviceId" -> {
                val androidId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                // Берём первые 8 символов — достаточно уникально
                result.success("device-${androidId.take(8)}")
            }

            "start" -> {
                val carrier  = call.argument<String>("carrier")  ?: "jitsi"
                val roomId   = call.argument<String>("roomId")   ?: ""
                val clientId = call.argument<String>("clientId") ?: ""
                val key      = call.argument<String>("key")      ?: ""

                if (roomId.isEmpty() || key.isEmpty() || clientId.isEmpty()) {
                    result.error("INVALID_ARGS", "roomId, clientId and key are required", null)
                    return
                }

                try {
                    Mobile.start(carrier, roomId, clientId, key)
                    Log.i(TAG, "olcrtc started: carrier=$carrier clientId=$clientId")
                    result.success(true)
                } catch (e: Exception) {
                    Log.e(TAG, "olcrtc start failed", e)
                    result.error("START_FAILED", e.message, null)
                }
            }

            "stop" -> {
                try {
                    Mobile.stop()
                    result.success(true)
                } catch (e: Exception) {
                    result.error("STOP_FAILED", e.message, null)
                }
            }

            "isRunning" -> {
                result.success(Mobile.isRunning())
            }

            else -> result.notImplemented()
        }
    }
}
