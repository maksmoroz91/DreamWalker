package com.example.vpn_app

enum class OlcrtcStopReason {
    FlutterEngineDetached,
    UserRequest,
    VpnServiceStopped,
    VpnRevoked,
}

object OlcrtcLifecyclePolicy {
    fun shouldStopNative(reason: OlcrtcStopReason): Boolean =
        reason != OlcrtcStopReason.FlutterEngineDetached
}