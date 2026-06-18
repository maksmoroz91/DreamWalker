package com.example.vpn_app

object OlcrtcLogManager {
    private val listeners = mutableListOf<(String) -> Unit>()
    private val logBuffer = ArrayDeque<String>()
    private const val BUFFER_SIZE = 500

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        logBuffer.forEach {
            try { listener(it) } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun log(line: String) {
        logBuffer.addLast(line)
        if (logBuffer.size > BUFFER_SIZE) {
            logBuffer.removeFirst()
        }
        listeners.forEach {
            try { it(line) } catch (_: Exception) {}
        }
    }
}