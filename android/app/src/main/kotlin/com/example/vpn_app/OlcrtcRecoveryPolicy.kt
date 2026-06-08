package com.example.vpn_app

object OlcrtcRecoveryPolicy {
    private const val RECONNECT_HANDSHAKE_FAILED = "handshake on reconnect failed"
    private const val FRAME_TOO_LARGE = "handshake: frame too large"
    private const val RECONNECT_EXHAUSTED = "client reconnect: exhausted"
    private const val HANDSHAKE_ATTEMPTS = "handshake attempts"
    private const val KEEPING_LISTENER_UP = "keeping listener up"
    private const val RECONNECT_HANDSHAKE_ATTEMPTS_EXHAUSTED =
        "reconnect handshake attempts exhausted"

    fun isReconnectHandshakeCorruption(logLine: String): Boolean {
        val line = logLine.lowercase()
        return line.contains(RECONNECT_HANDSHAKE_FAILED) && line.contains(FRAME_TOO_LARGE)
    }

    fun restartReason(logLine: String): String? {
        if (!shouldRestartNative(logLine)) {
            return null
        }

        return RECONNECT_HANDSHAKE_ATTEMPTS_EXHAUSTED
    }

    fun shouldRestartNative(logLine: String): Boolean {
        if (isReconnectHandshakeCorruption(logLine)) {
            return false
        }

        val line = logLine.lowercase()

        return (
                line.contains(RECONNECT_EXHAUSTED) &&
                        line.contains(HANDSHAKE_ATTEMPTS) &&
                        line.contains(KEEPING_LISTENER_UP)
                )
    }
}