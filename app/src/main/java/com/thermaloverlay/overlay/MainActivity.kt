/**
 * Main screen: starts/stops the overlay service, requests the draw-over-other-
 * apps permission, checks root access, toggles dual-battery mode, and opens
 * the frequency control screen.
 */
package com.thermaloverlay.overlay

import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import com.thermaloverlay.overlay.freqcontrol.FreqControlActivity
import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.ui.FloatMonitor

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var dualBatterySwitch: Switch
    private lateinit var rootWarning: LinearLayout
    private lateinit var rootWarningText: TextView
    private lateinit var freqControlButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    private var hasRoot: Boolean? = null

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

        dualBatterySwitch.setOnCheckedChangeListener { _, checked ->
            OverlayPrefs.setDualBattery(this, checked)
        }

        checkRootAccess()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun checkRootAccess() {
        rootWarningText.text = getString(R.string.root_checking)
        rootWarning.visibility = View.VISIBLE

        Thread {
            val result = try {
                KeepShellPublic.checkRoot()
            } catch (ex: Exception) {
                false
            }
            mainHandler.post {
                hasRoot = result
                rootWarning.visibility = if (result) View.GONE else View.VISIBLE
                rootWarningText.text = getString(R.string.root_warning)
            }
        }.start()
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    }

    private fun onStartClicked() {
        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (OverlayPrefs.isEnabled(this) || FloatMonitor.show) {
            refreshUi()
            return
        }

        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        OverlayPrefs.setEnabled(this, true)
        refreshUi()
    }

    private fun onStopClicked() {
        stopService(Intent(this, OverlayService::class.java))
        OverlayPrefs.setEnabled(this, false)
        refreshUi()
    }

    private fun refreshUi() {
        val hasPermission = hasOverlayPermission()
        val enabled = OverlayPrefs.isEnabled(this)

        statusText.text = when {
            !hasPermission -> getString(R.string.status_no_permission)
            enabled -> getString(R.string.status_enabled)
            else -> getString(R.string.status_disabled)
        }

        startButton.isEnabled = !enabled
        startButton.text = if (!hasPermission) getString(R.string.grant_permission) else getString(R.string.start_overlay)
        stopButton.isEnabled = enabled

        dualBatterySwitch.isChecked = OverlayPrefs.isDualBattery(this)
    }
}
