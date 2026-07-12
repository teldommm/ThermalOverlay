/**
 * Returns the correct overlay window type constant for the running Android
 * version.
 */
package com.thermaloverlay.overlay.utils

import android.os.Build
import android.view.WindowManager

object WindowCompatHelper {
    fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
    }
}
