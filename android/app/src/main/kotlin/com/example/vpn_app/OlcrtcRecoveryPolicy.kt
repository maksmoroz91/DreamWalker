package com.example.vpn_app

object OlcrtcRecoveryPolicy {
    private val terminalRules = listOf(
        listOf("frame too large") to "smux_desync_frame_too_large",
        listOf("conference end") to "jitsi_conference_ended",
        listOf("exhausted", "handshake attempts") to "handshake_retry_exhausted",
    )

    fun restartReason(logLine: String): String? {
        val line = logLine.lowercase()

        terminalRules.firstOrNull { (patterns, _) -> patterns.all { line.contains(it) } }
            ?.let { return it.second }

        return null
    }
}