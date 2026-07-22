/**
 * Holds the currently-tracked foreground package name, written by
 * ForegroundAppService. Thread monitor and framerate recorder both read
 * this to know which app they're currently attached to.
 */
package com.thermaloverlay.overlay.data

object GlobalStatus {
    @Volatile
    var lastPackageName: String = ""
}
