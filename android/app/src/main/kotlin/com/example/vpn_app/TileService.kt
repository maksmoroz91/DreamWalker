package com.example.vpn_app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class TileService : TileService() {
    companion object {
        private const val TAG = "TileService"
    }

    override fun onClick() {
        super.onClick()
        val isCurrentlyActive = AppVpnService.isActive
        if (isCurrentlyActive) {
            disconnect()
            return
        }
        if (VpnService.prepare(this) != null) {
            openAppForPermission()
            return
        }
        connectDirectly()
    }

    private fun connectDirectly() {
        setTileState(connecting = true)
        val config = OlcrtcConfig.load(applicationContext)
        if (config == null) {
            Log.e(TAG, "Cannot start VPN from tile: invalid/missing config")
            setTileState(connecting = false, active = false)
            return
        }

        startVpnService("CONNECT")

        Handler(Looper.getMainLooper()).postDelayed({
            val clientId = getDeviceIdSync()
            OlcrtcNativeRunner.start(
                context = applicationContext,
                carrier = config.carrier,
                roomId = config.roomId,
                clientId = clientId,
                key = config.key,
            ) { success, error ->
                if (success) {
                    Log.i(TAG, "olcrtc started from tile successfully")
                    setTileState(connecting = false, active = true)
                } else {
                    Log.e(TAG, "olcrtc failed to start from tile: $error")
                    setTileState(connecting = false, active = false)
                    startVpnService("DISCONNECT") // Откат, если не вышло
                }
            }
        }, 1000)
    }

    private fun disconnect() {
        setTileState(connecting = false, active = false)
        startVpnService("DISCONNECT")
        OlcrtcNativeRunner.stop(OlcrtcStopReason.UserRequest)
    }

    private fun openAppForPermission() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("auto_start_vpn", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        collapseTileWithIntent(mainIntent)
    }

    private fun startVpnService(actionName: String) {
        val intent = Intent(this, AppVpnService::class.java).apply {
            action = actionName
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun getDeviceIdSync(): String {
        val prefs = applicationContext.getSharedPreferences("olcrtc_prefs", android.content.Context.MODE_PRIVATE)
        val id = prefs.getString("device_id", null) ?: run {
            val generated = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("device_id", generated).apply()
            generated
        }
        return "device-${id.take(8)}"
    }

    private fun collapseTileWithIntent(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivity(intent)
        }
    }

    private fun setTileState(connecting: Boolean, active: Boolean = false) {
        qsTile?.apply {
            state = if (connecting || active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        qsTile?.apply {
            state = if (AppVpnService.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}