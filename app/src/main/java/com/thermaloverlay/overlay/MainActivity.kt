/**
 * Main screen: every overlay (load monitor, mini monitor, process monitor,
 * thread monitor, framerate recorder) is its own switch — there's no single
 * master Start/Stop anymore. OverlayService starts itself the moment any one
 * switch turns on and stops itself once the last one turns off. Also
 * requests the draw-over-other-apps permission, checks root access, toggles
 * dual-battery mode, and opens the frequency control / FPS history screens.
 */
package com.thermaloverlay.overlay

import android.content.Context
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

class MainActivity : AppCompatActivity() {
    private lateinit var overlayPermissionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var loadMonitorSwitch: Switch
    private lateinit var dualBatterySwitch: Switch
    private lateinit var miniMonitorSwitch: Switch
    private lateinit var processMonitorSwitch: Switch
    private lateinit var threadMonitorSwitch: Switch
    private lateinit var fpsRecorderSwitch: Switch
    private lateinit var rootWarning: LinearLayout
    private lateinit var rootWarningText: TextView
    private lateinit var freqControlButton: Button
    private lateinit var fpsHistoryButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    private var hasRoot: Boolean? = null

    // refreshUi() sets each switch's isChecked to match persisted state.
    // Switch fires its change listener on ANY value change, programmatic or
    // not — so the first time refreshUi() runs, setting loadMonitorSwitch to
    // its default (checked=true, since unlike the other four it defaults on)
    // would fire the listener exactly as if the user had tapped it: permission
    // missing → it reverts itself to false AND persists that reverted value,
    // AND redirects to Settings — all from just opening the screen. This flag
    // makes refreshUi's assignments inert.
    private var suppressSwitchEvents = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayPermissionButton = findViewById(R.id.overlay_permission_button)
        accessibilityButton = findViewById(R.id.accessibility_button)
        loadMonitorSwitch = findViewById(R.id.load_monitor_switch)
        dualBatterySwitch = findViewById(R.id.dual_battery_switch)
        miniMonitorSwitch = findViewById(R.id.mini_monitor_switch)
        processMonitorSwitch = findViewById(R.id.process_monitor_switch)
        threadMonitorSwitch = findViewById(R.id.thread_monitor_switch)
        fpsRecorderSwitch = findViewById(R.id.fps_recorder_switch)
        rootWarning = findViewById(R.id.root_warning)
        rootWarningText = findViewById(R.id.root_warning_text)
        freqControlButton = findViewById(R.id.freq_control_button)
        fpsHistoryButton = findViewById(R.id.fps_history_button)

        overlayPermissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        freqControlButton.setOnClickListener {
            startActivity(Intent(this, FreqControlActivity::class.java))
        }

        fpsHistoryButton.setOnClickListener {
            startActivity(Intent(this, ActivityFpsChart::class.java))
        }

        dualBatterySwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            OverlayPrefs.setDualBattery(this, checked)
        }

        bindMonitorSwitch(
            loadMonitorSwitch, OverlayService.ACTION_TOGGLE_LOAD, requireAccessibility = false,
            isEnabled = OverlayPrefs::isLoadMonitorEnabled, setEnabled = OverlayPrefs::setLoadMonitorEnabled
        )
        bindMonitorSwitch(
            miniMonitorSwitch, OverlayService.ACTION_TOGGLE_MINI, requireAccessibility = false,
            isEnabled = OverlayPrefs::isMiniMonitorEnabled, setEnabled = OverlayPrefs::setMiniMonitorEnabled
        )
        bindMonitorSwitch(
            processMonitorSwitch, OverlayService.ACTION_TOGGLE_PROCESS, requireAccessibility = false,
            isEnabled = OverlayPrefs::isProcessMonitorEnabled, setEnabled = OverlayPrefs::setProcessMonitorEnabled
        )
        bindMonitorSwitch(
            threadMonitorSwitch, OverlayService.ACTION_TOGGLE_THREAD, requireAccessibility = true,
            isEnabled = OverlayPrefs::isThreadMonitorEnabled, setEnabled = OverlayPrefs::setThreadMonitorEnabled
        )
        bindMonitorSwitch(
            fpsRecorderSwitch, OverlayService.ACTION_TOGGLE_FPS, requireAccessibility = true,
            isEnabled = OverlayPrefs::isFpsRecorderEnabled, setEnabled = OverlayPrefs::setFpsRecorderEnabled
        )

        checkRootAccess()
    }

    override fun onResume() {
        super.onResume()
        syncServiceState()
        refreshUi()
    }

    // The switches are the source of truth; this makes reality match them
    // instead of requiring an explicit Start. Matters most on first launch,
    // where the load monitor defaults to enabled but nothing has ever
    // started the service — without this, the switch would show "on" while
    // nothing actually runs until some switch gets flipped.
    private fun syncServiceState() {
        if (hasOverlayPermission() && OverlayPrefs.anyMonitorEnabled(this) && !OverlayPrefs.isEnabled(this)) {
            val serviceIntent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    // Shared by every monitor switch (load/mini/process/thread/fps) so the
    // permission-check → persist → live-toggle sequence lives in one place
    // instead of five near-identical copies — the process monitor and the
    // FPS history screen both drifted from this exact sequence before by
    // being hand-copied, so keeping just one copy is the point, not a
    // stylistic preference.
    private fun bindMonitorSwitch(
        switch: Switch,
        toggleAction: String,
        requireAccessibility: Boolean,
        isEnabled: (Context) -> Boolean,
        setEnabled: (Context, Boolean) -> Unit
    ) {
        switch.setOnCheckedChangeListener { switchView, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener

            if (checked && !hasOverlayPermission()) {
                switchView.isChecked = false
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnCheckedChangeListener
            }
            if (checked && requireAccessibility && !hasAccessibilityPermission()) {
                switchView.isChecked = false
                Toast.makeText(this, getString(R.string.accessibility_required_toast), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnCheckedChangeListener
            }

            setEnabled(this, checked)

            // Skip the round-trip if we're turning off while the service
            // isn't even running — otherwise this would spin it up just to
            // have it immediately self-stop (see stopIfNothingEnabled()).
            if (!checked && !OverlayPrefs.isEnabled(this)) return@setOnCheckedChangeListener

            val serviceIntent = Intent(this, OverlayService::class.java).setAction(toggleAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
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

    private fun refreshUi() {
        overlayPermissionButton.text = if (hasOverlayPermission()) {
            getString(R.string.overlay_permission_status_enabled)
        } else {
            getString(R.string.overlay_permission_status_disabled)
        }

        accessibilityButton.text = if (hasAccessibilityPermission()) {
            getString(R.string.accessibility_status_enabled)
        } else {
            getString(R.string.accessibility_status_disabled)
        }

        suppressSwitchEvents = true
        loadMonitorSwitch.isChecked = OverlayPrefs.isLoadMonitorEnabled(this)
        dualBatterySwitch.isChecked = OverlayPrefs.isDualBattery(this)
        miniMonitorSwitch.isChecked = OverlayPrefs.isMiniMonitorEnabled(this)
        processMonitorSwitch.isChecked = OverlayPrefs.isProcessMonitorEnabled(this)
        threadMonitorSwitch.isChecked = OverlayPrefs.isThreadMonitorEnabled(this)
        fpsRecorderSwitch.isChecked = OverlayPrefs.isFpsRecorderEnabled(this)
        suppressSwitchEvents = false
    }
}
