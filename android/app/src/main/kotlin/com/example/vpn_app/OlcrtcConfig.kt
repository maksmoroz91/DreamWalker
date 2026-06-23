package com.example.vpn_app

import android.content.Context
import android.util.Log

object OlcrtcConfig {
    private const val TAG = "OlcrtcConfig"
    private const val FLUTTER_PREFS_FILE = "FlutterSharedPreferences"
    private const val PREF_KEY_ROOM_ID = "flutter.custom_jitsi_room_id"
    private const val ENV_ASSET_PATH = "flutter_assets/.env"

    data class Config(
        val carrier: String,
        val roomId: String,
        val key: String,
    )

    private var cachedEnv: Map<String, String>? = null

    private fun loadEnv(context: Context): Map<String, String> {
        cachedEnv?.let { return it }

        val result = mutableMapOf<String, String>()
        try {
            context.assets.open(ENV_ASSET_PATH).bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim()
                    var value = line.substring(idx + 1).trim()

                    if (value.length >= 2 &&
                        ((value.startsWith("\"") && value.endsWith("\"")) ||
                                (value.startsWith("'") && value.endsWith("'")))
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    result[key] = value
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read .env from assets ($ENV_ASSET_PATH)", e)
        }

        cachedEnv = result
        return result
    }

    private fun readCustomRoomId(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(FLUTTER_PREFS_FILE, Context.MODE_PRIVATE)
            prefs.getString(PREF_KEY_ROOM_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read custom room id from SharedPreferences", e)
            null
        }
    }

    fun load(context: Context): Config? {
        val env = loadEnv(context)

        val roomId = readCustomRoomId(context) ?: env["JITSI_ROOM_ID"] ?: ""
        val key = env["OLCRTC_KEY"] ?: ""
        val carrier = env["OLCRTC_CARRIER"] ?: "jitsi"

        if (roomId.isEmpty()) {
            Log.e(TAG, "roomId is empty (no SharedPreferences override, no JITSI_ROOM_ID in .env)")
            return null
        }
        if (key.isEmpty()) {
            Log.e(TAG, "OLCRTC_KEY not set in .env")
            return null
        }
        if (key.length != 64) {
            Log.e(TAG, "OLCRTC_KEY must be 64 hex chars")
            return null
        }

        return Config(carrier = carrier, roomId = roomId, key = key)
    }
}