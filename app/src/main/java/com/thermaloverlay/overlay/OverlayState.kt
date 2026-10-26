/**
 * Process-wide truth about whether the overlay service is alive. Replaces the
 * old trio of "enabled" preference + service flag + window flag, which could
 * disagree after a crash or kill. A fresh process always starts with false,
 * and the service sets it from onCreate/onDestroy, so it can't go stale.
 */
package com.thermaloverlay.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object OverlayState {
    private val _running = MutableStateFlow(false)

    val running: StateFlow<Boolean> = _running

    var isRunning: Boolean
        get() = _running.value
        internal set(value) {
            _running.value = value
        }
}
