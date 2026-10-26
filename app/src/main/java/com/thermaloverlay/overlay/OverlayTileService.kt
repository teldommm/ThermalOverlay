/**
 * Quick Settings tile that starts or stops the overlay in one tap without
 * opening MainActivity. Its state follows [OverlayState], so it also updates
 * when the overlay is closed from elsewhere (HUD double tap, notification).
 */
package com.thermaloverlay.overlay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        collectJob?.cancel()
        collectJob = scope.launch {
            OverlayState.running.collect { running -> updateTileState(running) }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()

        if (OverlayState.isRunning) {
            OverlayService.requestStop(this)
            updateTileState(false)
            return
        }

        // Known-missing root: the main screen explains it and can re-check.
        // (Unknown root is checked by the service, which also grants "display
        // over other apps" through root when it is missing.)
        if (RootState.granted.value == false) {
            openMainActivity()
            return
        }

        // No optimistic "active" state: the service may still refuse to start
        // (no root), so the tile follows OverlayState instead.
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    // The Intent overload is only reached below API 34 (see the version check);
    // lint can't see through that.
    @SuppressLint("StartActivityAndCollapseDeprecated")
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

    private fun updateTileState(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_overlay)
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }
}
