package com.example.vpn_app

import android.content.Context
import androidx.core.content.edit
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Тонкая обёртка над OlcrtcNativeRunner. Раньше вся логика старта/стопа/
 * watchdog жила прямо здесь и привязывалась к жизни FlutterEngine; теперь
 * она вынесена в process-wide OlcrtcNativeRunner, чтобы TileService мог
 * управлять тем же туннелем и тем же watchdog'ом без открытия приложения.
 * Контракт MethodChannel/EventChannel ("olcrtc_channel", "olcrtc_logs",
 * "vpn_events") не менялся -- Dart-сторона не требует изменений.
 */
class OlcrtcPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var logChannel: EventChannel
    private var logSink: EventChannel.EventSink? = null
    private var vpnEventSink: EventChannel.EventSink? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
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
                }
                override fun onCancel(arguments: Any?) { vpnEventSink = null }
            })

        OlcrtcNativeRunner.ensureInitialized(context)
        OlcrtcNativeRunner.logListener = { line -> logSink?.success(line) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        logSink = null
        vpnEventSink = null
        OlcrtcNativeRunner.logListener = null

        if (OlcrtcLifecyclePolicy.shouldStopNative(OlcrtcStopReason.FlutterEngineDetached)) {
            OlcrtcNativeRunner.stop(OlcrtcStopReason.FlutterEngineDetached)
        } else {
            android.util.Log.i("OlcrtcPlugin", "Flutter engine detached; keeping olcrtc running for VPN service")
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

                OlcrtcNativeRunner.start(context, carrier, roomId, clientId, key) { success, error ->
                    if (success) {
                        result.success(true)
                    } else {
                        result.error("START_FAILED", error, null)
                    }
                }
            }

            "stop" -> {
                OlcrtcNativeRunner.stop(OlcrtcStopReason.UserRequest) {
                    result.success(true)
                }
            }

            "isRunning" -> result.success(OlcrtcNativeRunner.isRunning())

            else -> result.notImplemented()
        }
    }
}