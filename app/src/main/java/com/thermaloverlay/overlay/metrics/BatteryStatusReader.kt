/**
 * Reads battery capacity, charging status, voltage, and temperature.
 */
package com.thermaloverlay.overlay.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.thermaloverlay.overlay.shell.KeepShellPublic

object BatteryStatusReader {
    var batteryCapacity = -1
        private set
    var batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN
        private set

    var batteryVoltage = -1.0
        private set

    var temperatureCurrent = -1.0
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

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                if (status != Int.MIN_VALUE) batteryStatus = status
            }

            calibrateCurrentUnitDivisor(batteryManager)
        }

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tempTenths > 0) {
            val celsius = tempTenths / 10.0
            if (celsius > 10 && celsius < 100) temperatureCurrent = celsius
        }

        readVoltage()?.let { batteryVoltage = it }

        lastUpdateTime = System.currentTimeMillis()
    }

    private fun readVoltage(): Double? {
        val path = voltagePath ?: run {
            val found = voltagePathCandidates.firstOrNull { candidate ->
                val value = KeepShellPublic.doCmdSync("cat $candidate 2>/dev/null").trim()
                value.isNotEmpty() && value.length >= 2 && value.all { it.isDigit() }
            }
            voltagePath = found ?: ""
            found
        }
        if (path.isNullOrEmpty()) return null

        val raw = KeepShellPublic.doCmdSync("cat $path 2>/dev/null").trim()
        if (raw.length < 2) return null

        return try {
            val trimmed = if (raw.length > 4) raw.substring(0, 4) else raw
            val withDecimal = trimmed.substring(0, 1) + "." + trimmed.substring(1)
            withDecimal.toDoubleOrNull()
        } catch (ex: Exception) {
            null
        }
    }
}
