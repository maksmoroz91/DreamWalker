package com.example.vpn_app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ArrayBlockingQueue

class VpnPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private val pendingPackets = ArrayBlockingQueue<ByteArray>(500)
    private var streamActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private var plugin: VpnPlugin? = null
        fun sendPacket(packet: ByteArray) {
            plugin?.let {
                if (it.streamActive) {
                    it.mainHandler.post {
                        it.eventSink?.success(packet)
                    }
                } else {
                    if (it.pendingPackets.remainingCapacity() > 0) {
                        it.pendingPackets.offer(packet)
                    }
                }
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, "vpn_channel")
        eventChannel = EventChannel(binding.binaryMessenger, "vpn_events")
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
        plugin = this
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        plugin = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                val intent = Intent(context, VpnService::class.java).apply { action = "CONNECT" }
                context.startService(intent)
                result.success(true)
            }
            "stop" -> {
                val intent = Intent(context, VpnService::class.java).apply { action = "DISCONNECT" }
                context.startService(intent)
                result.success(true)
            }
            "write" -> {
                // Пока не реализовано — будет нужно для обратного канала позже
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events
        streamActive = true
        mainHandler.post {
            while (pendingPackets.isNotEmpty()) {
                eventSink?.success(pendingPackets.poll())
            }
        }
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        streamActive = false
    }
}