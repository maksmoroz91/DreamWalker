package com.example.vpn_app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pendingResult: MethodChannel.Result? = null
    private val vpnRequestCode = 100
    private val vpnRequestCodeAutoStart = 101

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
                        startActivityForResult(intent, vpnRequestCode)
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
                        startVpnService()
                        result.success(true)
                    }
                    "stop" -> {
                        stopVpnService()
                        result.success(true)
                    }
                    "getStatus" -> {
                        result.success(AppVpnService.isActive)
                    }
                    else -> result.notImplemented()
                }
            }

        handleAutoStartVpnIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoStartVpnIntent(intent)
    }

    private fun handleAutoStartVpnIntent(intent: Intent) {
        if (intent.getBooleanExtra("auto_start_vpn", false)) {
            intent.removeExtra("auto_start_vpn")
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, vpnRequestCodeAutoStart)
            } else {
                startVpnService()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == vpnRequestCode) {
            pendingResult?.success(resultCode == RESULT_OK)
            pendingResult = null
        } else if (requestCode == vpnRequestCodeAutoStart) {
            if (resultCode == RESULT_OK) {
                startVpnService()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AppVpnService::class.java).apply {
            action = "CONNECT"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, AppVpnService::class.java).apply {
            action = "DISCONNECT"
        }
        startService(intent)
    }
}