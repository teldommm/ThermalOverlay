/**
 * Reads named thermal zones (GPU/DDR/Camera) from
 * /sys/class/thermal/thermal_zone*, matching by the zone's `type` file
 * against keyword substrings. CPU is deliberately NOT handled here — it's
 * read via CpuLoadUtils.getCpuTemperatureText(), which already exists and
 * is proven (matches "cpu-0" specifically, not a bare "cpu" substring,
 * and filters out implausible near-zero readings). This class's own
 * generic "cpu" substring match was tried first and got exactly that
 * wrong: too loose a keyword, and no floor on what counts as a real
 * reading, so a matched-but-bogus zone would display as-is.
 *
 * Simplification versus a fuller implementation: the source app resolves
 * these through a bundled root daemon with per-vendor detection scripts
 * (assets/kr-script/{qualcomm,mtk,miui,...}) we don't have visibility into.
 * This just scans standard thermal_zone type strings for known keywords —
 * works on many devices, but not ones with cryptic zone names (e.g.
 * "tsens_tz_sensor0" with no hint of what it measures). On those this
 * simply shows fewer lines than the original, same graceful degradation
 * as its own per-sensor availability check.
 */
package com.thermaloverlay.overlay.metrics

import com.thermaloverlay.overlay.shell.KeepShellPublic

object TemperatureSensorUtils {
    private data class Sensor(val label: String, val keywords: List<String>)

    private val sensorDefs = listOf(
        Sensor("GPU", listOf("gpu")),
        Sensor("DDR", listOf("ddr")),
        Sensor("CAM", listOf("cam"))
    )

    // label -> the thermal_zone paths that matched it (resolved once; the
    // zone-to-type mapping doesn't change at runtime, only the temp does).
    private var resolved: LinkedHashMap<String, List<String>>? = null

    private fun resolveZones(): LinkedHashMap<String, List<String>> {
        resolved?.let { return it }

        val output = KeepShellPublic.doCmdSync(
            "for z in /sys/class/thermal/thermal_zone*; do echo \"\$z|\$(cat \$z/type 2>/dev/null)\"; done"
        )
        val zones = ArrayList<Pair<String, String>>()
        for (line in output.split("\n".toRegex())) {
            val parts = line.split("|")
            if (parts.size == 2 && parts[1].isNotBlank()) {
                zones.add(parts[0].trim() to parts[1].trim().lowercase())
            }
        }

        // Every real device exposes at least some thermal zones — finding
        // none means the root shell probably wasn't ready yet (this can
        // run on the very first tick, right as the daemon is still coming
        // up), not that this device genuinely has zero zones. Don't
        // memoize that as if it were a real, permanent answer, or a single
        // early hiccup would leave the monitor showing only battery temp
        // for the rest of the process's life.
        if (zones.isEmpty()) return LinkedHashMap()

        val map = LinkedHashMap<String, List<String>>()
        for (sensor in sensorDefs) {
            val matches = zones.filter { (_, type) -> sensor.keywords.any { type.contains(it) } }
            if (matches.isNotEmpty()) {
                map[sensor.label] = matches.map { it.first }
            }
        }
        resolved = map
        return map
    }

    // label -> current reading in °C, only for sensors actually present on
    // this device. A label backed by more than one zone reports the
    // highest of them — what matters for "is this chip running hot" is
    // the worst reading, not an average.
    fun readAvailable(): LinkedHashMap<String, Double> {
        val zones = resolveZones()
        val result = LinkedHashMap<String, Double>()
        if (zones.isEmpty()) return result

        val allPaths = zones.values.flatten()
        val output = KeepShellPublic.doCmdSync(
            allPaths.joinToString("\n") { "echo \"$it|\$(cat $it/temp 2>/dev/null)\"" }
        )
        val tempByPath = HashMap<String, Int>()
        for (line in output.split("\n".toRegex())) {
            val parts = line.split("|")
            if (parts.size == 2) {
                parts[1].trim().toIntOrNull()?.let { tempByPath[parts[0].trim()] = it }
            }
        }

        for ((label, paths) in zones) {
            val milliDegrees = paths.mapNotNull { tempByPath[it] }.maxOrNull() ?: continue
            // Same floor CpuLoadUtils.getCpuTemperatureText() uses: a real
            // device temperature is never at or below 1°C, so anything
            // this low means the zone returned an error code or a
            // disabled-sensor placeholder, not an actual reading.
            if (milliDegrees <= 1000) continue
            result[label] = milliDegrees / 1000.0
        }
        return result
    }
}
