/**
 * Returns the overlay window type constant (minSdk is 26, so always the
 * application-overlay type).
 */
package com.thermaloverlay.overlay.utils

import android.view.WindowManager

object WindowCompatHelper {
    fun overlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
}
