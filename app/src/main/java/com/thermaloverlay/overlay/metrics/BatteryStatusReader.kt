/**
 * Reads battery capacity, charging status, voltage, and temperature.
 */
package com.thermaloverlay.overlay.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.thermaloverlay.overlay.shell.SysReader

object BatteryStatusReader {
    var batteryCapacity = -1
        private set
    var batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN
        private set

    var batteryVoltage = -1.0
        private set

    /** Battery temperature in °C, NaN until the first valid reading. */
    var temperatureCurrent = Double.NaN
        private set

    var currentUnitDivisor = -1000
        private set
    private var divisorCalibrated = false

    private fun calibrateCurrentUnitDivisor(batteryManager: BatteryManager) {
        if (divisorCalibrated) return

        if (Build.MANUFACTURER.uppercase() == "XIAOMI") {
            currentUnitDivisor = -1000
            divisorCalibrated = true
            return
        }

        val currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                currentUnitDivisor = when {
                    currentNow > 20000 -> -1000
                    currentNow < -20000 -> 1000
                    currentNow > 0 -> -1
                    else -> 1
                }
                divisorCalibrated = true
            }
            BatteryManager.BATTERY_STATUS_CHARGING -> {
                currentUnitDivisor = when {
                    currentNow > 20000 -> 1000
                    currentNow < -20000 -> -1000
                    currentNow > 0 -> 1
                    else -> -1
                }
                divisorCalibrated = true
            }
            else -> {
                // Not charging/discharging yet: keep the default and calibrate later.
            }
        }
    }

    private var lastUpdateTime = 0L
    private var voltagePath: String? = null

    private val voltagePathCandidates = listOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/devices/platform/soc/soc:oplus,mms_gauge/oplus_mms/gauge/battery/voltage_now"
    )

    fun update(context: Context) {
        if (System.currentTimeMillis() - lastUpdateTime < 5000 && batteryCapacity > -1) {
            return
        }

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (batteryManager != null) {
            val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (capacity in 0..100) batteryCapacity = capacity

            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            if (status != Int.MIN_VALUE) batteryStatus = status

            calibrateCurrentUnitDivisor(batteryManager)
        }

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (tempTenths != Int.MIN_VALUE) {
            val celsius = tempTenths / 10.0
            // Sanity range only; a cold phone (< 10 °C) is a perfectly valid reading.
            if (celsius > -40 && celsius < 120) temperatureCurrent = celsius
        }

        val volts = readVoltage() ?: intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            ?.takeIf { it > 0 }?.let { it / 1000.0 }
        volts?.let { batteryVoltage = it }

        lastUpdateTime = System.currentTimeMillis()
    }

    private fun readVoltage(): Double? {
        var path = voltagePath
        if (path == null) {
            // Probe once; remember the answer only if every candidate was
            // actually read (a shell failure must not disable this for good).
            var found = ""
            for (candidate in voltagePathCandidates) {
                val value = SysReader.readOrNull(candidate) ?: return null
                if (parseVoltage(value) != null) {
                    found = candidate
                    break
                }
            }
            voltagePath = found
            path = found
        }
        if (path.isEmpty()) return null
        return parseVoltage(SysReader.read(path))
    }

    /** Accepts µV ("4123456") and mV ("4123") readings; returns volts. */
    internal fun parseVoltage(raw: String): Double? {
        val v = raw.trim().toLongOrNull() ?: return null
        return when {
            v > 100_000 -> v / 1_000_000.0
            v > 1_000 -> v / 1_000.0
            else -> null
        }
    }
}
