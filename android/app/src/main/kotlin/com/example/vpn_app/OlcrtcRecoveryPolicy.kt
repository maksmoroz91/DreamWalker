package com.example.vpn_app

object OlcrtcRecoveryPolicy {
    // Первые совпадения имеют приоритет
    private val rules = listOf(
        listOf("frame too large") to "smux_desync_frame_too_large",
        listOf("handshake on reconnect failed") to "handshake_on_reconnect_failed",
        listOf("openstream failed") to "smux_open_stream_failed",
        listOf("wait jingle failed") to "jingle_session_initiate_failed",
        listOf("jitsi reconnect failed") to "jitsi_reconnect_failed",
        listOf("reconnect reason=liveness") to "liveness_probe_failed",

        listOf("rejoin failed", "context deadline exceeded") to "xmpp_deadline_exceeded",
        listOf("full reconnect", "rejoin failed") to "full_reconnect_exhausted",
        listOf("full reconnect", "wait reinitiate failed") to "full_reconnect_exhausted",

        listOf("exhausted") to "handshake_attempts_exhausted",
    )

    fun shouldRestartNative(logLine: String): Boolean =
        restartReason(logLine) != null

    fun restartReason(logLine: String): String? {
        val line = logLine.lowercase()
        return rules.firstOrNull { (patterns, _) ->
            patterns.all { line.contains(it) }
        }?.second
    }
}