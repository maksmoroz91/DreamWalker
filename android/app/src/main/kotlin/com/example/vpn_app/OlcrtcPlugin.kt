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

class OlcrtcPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var logChannel: EventChannel
    private var logSink: EventChannel.EventSink? = null
    private var vpnEventSink: EventChannel.EventSink? = null

    // Главный Handler для безопасного вызова методов Flutter из любого потока
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "OlcrtcPlugin"
        lateinit var instance: OlcrtcPlugin
            private set
    }

    private val logWriter = object : LogWriter {
        override fun writeLog(line: String?) {
            line?.let {
                mainHandler.post {
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

    /**
     * Вызывается из фонового потока tun-reader в AppVpnService.
     * EventChannel.EventSink требует @UiThread, поэтому оборачиваем в mainHandler.
     */
    fun sendPacketToFlutter(packet: ByteArray) {
        mainHandler.post {
            vpnEventSink?.success(packet)
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        instance = this
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

        // Канал для получения пакетов с TUN (вызывается из AppVpnService)
        EventChannel(binding.binaryMessenger, "vpn_events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    vpnEventSink = events
                    Log.d(TAG, "vpn_events listener attached")
                }
                override fun onCancel(arguments: Any?) {
                    vpnEventSink = null
                    Log.d(TAG, "vpn_events listener detached")
                }
            })

        Mobile.setLogWriter(logWriter)
        Mobile.setProtector(socketProtector)

        // Канал для записи пакетов обратно в TUN (из Flutter в VpnService)
        MethodChannel(binding.binaryMessenger, "vpn_channel")
            .setMethodCallHandler { call, result ->
                if (call.method == "write") {
                    val packet = call.argument<ByteArray>("packet")
                    if (packet != null) {
                        VpnServiceInstance.get()?.writePacket(packet)
                        result.success(null)
                    } else {
                        result.error("INVALID", "No packet", null)
                    }
                } else {
                    result.notImplemented()
                }
            }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        logSink = null
        vpnEventSink = null
        try {
            Mobile.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
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
                val carrier = call.argument<String>("carrier") ?: "jitsi"
                val roomId = call.argument<String>("roomId") ?: ""
                val clientId = call.argument<String>("clientId") ?: ""
                val key = call.argument<String>("key") ?: ""

                if (roomId.isEmpty() || key.isEmpty() || clientId.isEmpty()) {
                    result.error("INVALID_ARGS", "roomId, clientId and key are required", null)
                    return
                }
                try {
                    Mobile.start(carrier, roomId, clientId, key, 0L, "", "")
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
                    Log.e(TAG, "stop error", e)
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