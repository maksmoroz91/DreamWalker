package com.example.vpn_app

import android.content.Intent
import android.net.VpnService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pendingResult: MethodChannel.Result? = null
    private val VPN_REQUEST_CODE = 100

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)


        VpnServiceInstance.flutterBinaryMessenger = flutterEngine.dartExecutor.binaryMessenger

        flutterEngine.plugins.add(OlcrtcPlugin())


        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "vpn_prepare")
            .setMethodCallHandler { call, result ->
                if (call.method == "prepare") {
                    val intent = VpnService.prepare(this)
                    if (intent != null) {
                        pendingResult = result
                        startActivityForResult(intent, VPN_REQUEST_CODE)
                    } else {
                        result.success(true)
                    }
                } else {
                    result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "vpn_service")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "start" -> {
                        startService(
                            Intent(this, AppVpnService::class.java).apply {
                                action = "CONNECT"
                            }
                        )
                        result.success(true)
                    }
                    "stop" -> {
                        startService(
                            Intent(this, AppVpnService::class.java).apply {
                                action = "DISCONNECT"
                            }
                        )
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST_CODE) {
            pendingResult?.success(resultCode == RESULT_OK)
            pendingResult = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}