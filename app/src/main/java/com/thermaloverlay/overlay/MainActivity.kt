/**
 * Main screen: starts/stops the overlay service, requests the draw-over-other-
 * apps permission, checks root access, toggles dual-battery mode, and opens
 * the frequency control screen.
 */
package com.thermaloverlay.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.thermaloverlay.overlay.freqcontrol.FreqControlActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var dualBatterySwitch: Switch
    private lateinit var rootWarning: LinearLayout
    private lateinit var rootWarningText: TextView
    private lateinit var freqControlButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var rootJob: Job? = null
    private var rootChecking = false

    // Asked once per click; the overlay starts whatever the answer is — the
    // notification is only a convenience (stop button), not a requirement.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startOverlayService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        dualBatterySwitch = findViewById(R.id.dual_battery_switch)
        rootWarning = findViewById(R.id.root_warning)
        rootWarningText = findViewById(R.id.root_warning_text)
        freqControlButton = findViewById(R.id.freq_control_button)

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }

        freqControlButton.setOnClickListener {
            startActivity(Intent(this, FreqControlActivity::class.java))
        }

        dualBatterySwitch.isChecked = OverlayPrefs.isDualBattery(this)
        dualBatterySwitch.setOnCheckedChangeListener { _, checked ->
            OverlayPrefs.setDualBattery(this, checked)
        }

        if (!RootState.isGranted) checkRootAccess(retryNow = false)
    }

    override fun onStart() {
        super.onStart()
        // Follows the real service state, so the screen also updates when the
        // overlay is closed from the HUD, the tile or the notification.
        stateJob = scope.launch {
            OverlayState.running.collect { refreshUi() }
        }
        rootJob = scope.launch {
            RootState.granted.collect { refreshUi() }
        }
    }

    override fun onResume() {
        super.onResume()
        // The overlay permission can be granted while we are in Settings.
        refreshUi()
    }

    override fun onStop() {
        stateJob?.cancel()
        stateJob = null
        rootJob?.cancel()
        rootJob = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun checkRootAccess(retryNow: Boolean) {
        rootChecking = true
        refreshUi()

        Thread {
            RootState.check(retryNow)
            mainHandler.post {
                if (isDestroyed) return@post
                rootChecking = false
                refreshUi()
            }
        }.start()
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun onStartClicked() {
        // No root = no overlay. The button turns into "check again" in that case.
        if (!RootState.isGranted) {
            checkRootAccess(retryNow = true)
            return
        }

        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (OverlayState.isRunning) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        if (OverlayState.isRunning) return
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    private fun onStopClicked() {
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun refreshUi() {
        val enabled = OverlayState.isRunning
        val root = RootState.granted.value
        val hasPermission = hasOverlayPermission()

        // The banner is shown while checking and whenever root is missing.
        rootWarning.visibility = if (rootChecking || root != true) View.VISIBLE else View.GONE
        rootWarningText.text = getString(if (rootChecking) R.string.root_checking else R.string.root_warning)

        statusText.text = when {
            enabled -> getString(R.string.status_enabled)
            root == false -> getString(R.string.status_no_root)
            !hasPermission -> getString(R.string.status_no_permission)
            else -> getString(R.string.status_disabled)
        }

        startButton.isEnabled = !enabled && !rootChecking
        startButton.text = when {
            root != true -> getString(R.string.check_root_again)
            !hasPermission -> getString(R.string.grant_permission)
            else -> getString(R.string.start_overlay)
        }
        stopButton.isEnabled = enabled

        freqControlButton.isEnabled = root == true
        freqControlButton.alpha = if (root == true) 1f else 0.4f
    }
}
