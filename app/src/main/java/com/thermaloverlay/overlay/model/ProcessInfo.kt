/**
 * Process info model for the process monitor. Trimmed to the fields
 * ProcessUtilsSimple actually populates and AdapterProcessMini/FloatTaskManager
 * actually read — the source app's ProcessInfo carries several memory/state
 * fields (rss, oomAdj, cGroup, ...) that nothing here ever sets or reads.
 */
package com.thermaloverlay.overlay.model

class ProcessInfo {
    var pid: Int = 0
    var name: String = ""
    var cpu: Float = 0f
    var command: String = ""
    var friendlyName: String = ""
}
