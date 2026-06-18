package com.example.vpn_app

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import mobile.Mobile
import java.util.concurrent.Executors
import androidx.core.content.edit

class OlcrtcPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var logChannel: EventChannel
    private var logSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val pendingLogsForSink = ArrayDeque<String>()
    private val sinkBufferSize = 500

    private val logListener: (String) -> Unit = { line ->
        mainHandler.post {
            if (logSink != null) {
                logSink?.success(line)
            } else {
                pendingLogsForSink.addLast(line)
                if (pendingLogsForSink.size > sinkBufferSize) {
                    pendingLogsForSink.removeFirst()
                }
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
                mainHandler.post {
                    while (pendingLogsForSink.isNotEmpty()) {
                        logSink?.success(pendingLogsForSink.removeFirst())
                    }
                }
            }
            override fun onCancel(arguments: Any?) {
                logSink = null
            }
        })


        OlcrtcLogManager.addListener(logListener)

        val statusChannel = EventChannel(binding.binaryMessenger, "vpn_status")
        statusChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                AppVpnService.statusSink = events
                events.success(AppVpnService.isActive)
            }
            override fun onCancel(arguments: Any?) {
                AppVpnService.statusSink = null
            }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        logSink = null
        OlcrtcLogManager.removeListener(logListener)
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
                result.success(true)
            }
            "stop" -> {
                bgExecutor.submit {
                    try {
                        if (Mobile.isRunning()) Mobile.stop()
                        mainHandler.post { result.success(true) }
                    } catch (e: Exception) {
                        mainHandler.post { result.error("STOP_FAILED", e.message, null) }
                    }
                }
            }
            "isRunning" -> result.success(Mobile.isRunning())
            else -> result.notImplemented()
        }
    }
}