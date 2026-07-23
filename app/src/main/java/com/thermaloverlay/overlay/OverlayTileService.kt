/**
 * Quick Settings tile that starts or stops the overlay in one tap without
 * opening MainActivity.
 */
package com.thermaloverlay.overlay

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.thermaloverlay.overlay.ui.FloatMonitor

class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        if (OverlayPrefs.isEnabled(this)) {
            stopService(Intent(this, OverlayService::class.java))
            OverlayPrefs.setEnabled(this, false)
            updateTileState(Tile.STATE_INACTIVE)
            return
        }

        if (!hasOverlayPermission()) {
            openMainActivity()
            return
        }

        if (OverlayPrefs.isEnabled(this) || FloatMonitor.show) {
            updateTileState(Tile.STATE_ACTIVE)
            return
        }

        // OverlayService now stops itself the moment no monitor is enabled
        // (see stopIfNothingEnabled()) — if every switch was left off, make
        // sure there's at least something to show, or the service would
        // start and immediately stop again right under this tap.
        if (!OverlayPrefs.anyMonitorEnabled(this)) {
            OverlayPrefs.setLoadMonitorEnabled(this, true)
        }

        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        OverlayPrefs.setEnabled(this, true)
        updateTileState(Tile.STATE_ACTIVE)
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {

            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        updateTileState(
            if (OverlayPrefs.isEnabled(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        )
    }

    private fun updateTileState(state: Int) {
        val tile = qsTile ?: return
        tile.state = state
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_overlay)
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }
}
