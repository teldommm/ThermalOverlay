/**
 * A single thread's CPU load, as read from `top -H`.
 */
package com.thermaloverlay.overlay.model

class ThreadInfo {
    var tid: Int = 0
    var cpuLoad: Double = 0.0
    var name: String = ""
}
