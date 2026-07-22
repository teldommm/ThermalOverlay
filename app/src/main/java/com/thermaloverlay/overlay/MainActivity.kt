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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.thermaloverlay.overlay.freqcontrol.FreqControlActivity
import com.thermaloverlay.overlay.activities.ActivityFpsChart
import com.thermaloverlay.overlay.shell.KeepShellPublic
import com.thermaloverlay.overlay.ui.FloatMonitor

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var dualBatterySwitch: Switch
    private lateinit var miniMonitorSwitch: Switch
    private lateinit var processMonitorSwitch: Switch
    private lateinit var threadMonitorSwitch: Switch
    private lateinit var fpsRecorderSwitch: Switch
    private lateinit var accessibilityButton: Button
    private lateinit var rootWarning: LinearLayout
    private lateinit var rootWarningText: TextView
    private lateinit var freqControlButton: Button
    private lateinit var fpsHistoryButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    private var hasRoot: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        dualBatterySwitch = findViewById(R.id.dual_battery_switch)
        miniMonitorSwitch = findViewById(R.id.mini_monitor_switch)
        processMonitorSwitch = findViewById(R.id.process_monitor_switch)
        threadMonitorSwitch = findViewById(R.id.thread_monitor_switch)
        fpsRecorderSwitch = findViewById(R.id.fps_recorder_switch)
        accessibilityButton = findViewById(R.id.accessibility_button)
        rootWarning = findViewById(R.id.root_warning)
        rootWarningText = findViewById(R.id.root_warning_text)
        freqControlButton = findViewById(R.id.freq_control_button)
        fpsHistoryButton = findViewById(R.id.fps_history_button)

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }

        freqControlButton.setOnClickListener {
            startActivity(Intent(this, FreqControlActivity::class.java))
        }

        fpsHistoryButton.setOnClickListener {
            startActivity(Intent(this, ActivityFpsChart::class.java))
        }

        dualBatterySwitch.setOnCheckedChangeListener { _, checked ->
            OverlayPrefs.setDualBattery(this, checked)
        }

        miniMonitorSwitch.setOnCheckedChangeListener { switchView, checked ->
            if (checked && !hasOverlayPermission()) {
                switchView.isChecked = false
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setMiniMonitorEnabled(this, checked)
            // Only meaningful while the main overlay service is already
            // running — otherwise the flag just takes effect on next Start.
            if (OverlayPrefs.isEnabled(this)) {
                val serviceIntent = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_TOGGLE_MINI)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }

        processMonitorSwitch.setOnCheckedChangeListener { switchView, checked ->
            if (checked && !hasOverlayPermission()) {
                switchView.isChecked = false
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setProcessMonitorEnabled(this, checked)
            if (OverlayPrefs.isEnabled(this)) {
                val serviceIntent = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_TOGGLE_PROCESS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        threadMonitorSwitch.setOnCheckedChangeListener { switchView, checked ->
            if (checked && !hasOverlayPermission()) {
                switchView.isChecked = false
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnCheckedChangeListener
            }
            if (checked && !hasAccessibilityPermission()) {
                switchView.isChecked = false
                Toast.makeText(this, getString(R.string.accessibility_required_toast), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setThreadMonitorEnabled(this, checked)
            if (OverlayPrefs.isEnabled(this)) {
                val serviceIntent = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_TOGGLE_THREAD)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }

        fpsRecorderSwitch.setOnCheckedChangeListener { switchView, checked ->
            if (checked && !hasOverlayPermission()) {
                switchView.isChecked = false
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnCheckedChangeListener
            }
            if (checked && !hasAccessibilityPermission()) {
                switchView.isChecked = false
                Toast.makeText(this, getString(R.string.accessibility_required_toast), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setFpsRecorderEnabled(this, checked)
            if (OverlayPrefs.isEnabled(this)) {
                val serviceIntent = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_TOGGLE_FPS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
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

    private fun hasAccessibilityPermission(): Boolean {
        val expected = "$packageName/${ForegroundAppService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
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
        miniMonitorSwitch.isChecked = OverlayPrefs.isMiniMonitorEnabled(this)
        processMonitorSwitch.isChecked = OverlayPrefs.isProcessMonitorEnabled(this)
        threadMonitorSwitch.isChecked = OverlayPrefs.isThreadMonitorEnabled(this)
        fpsRecorderSwitch.isChecked = OverlayPrefs.isFpsRecorderEnabled(this)

        accessibilityButton.text = if (hasAccessibilityPermission()) {
            getString(R.string.accessibility_status_enabled)
        } else {
            getString(R.string.accessibility_status_disabled)
        }
    }
}
