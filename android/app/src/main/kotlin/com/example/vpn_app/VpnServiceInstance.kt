package com.example.vpn_app

import io.flutter.plugin.common.BinaryMessenger
import java.lang.ref.WeakReference

object VpnServiceInstance {
    private var ref: WeakReference<AppVpnService>? = null
    var flutterBinaryMessenger: BinaryMessenger? = null
    fun set(service: AppVpnService) {
        ref = WeakReference(service)
    }

    fun clear() {
        ref = null
    }

    fun get(): AppVpnService? = ref?.get()
}