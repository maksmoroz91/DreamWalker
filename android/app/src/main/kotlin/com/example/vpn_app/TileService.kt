package com.example.vpn_app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class TileService : TileService() {

    override fun onClick() {
        super.onClick()
        val isCurrentlyActive = AppVpnService.isActive

        if (!isCurrentlyActive) {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    putExtra("auto_start_vpn", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                collapseTile(mainIntent)
                return
            }
        }

        qsTile?.apply {
            state = if (isCurrentlyActive) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }

        val intent = Intent(this, AppVpnService::class.java).apply {
            action = if (isCurrentlyActive) "DISCONNECT" else "CONNECT"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun collapseTile(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
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