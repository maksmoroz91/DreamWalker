package com.example.vpn_app

import java.lang.ref.WeakReference

/**
 * Синглтон для передачи ссылки на VpnService в OlcrtcPlugin.
 * WeakReference — чтобы не держать сервис живым искусственно.
 */
object VpnServiceInstance {
    private var ref: WeakReference<AppVpnService>? = null

    fun set(service: AppVpnService) {
        ref = WeakReference(service)
    }

    fun clear() {
        ref = null
    }

    fun get(): AppVpnService? = ref?.get()
}
