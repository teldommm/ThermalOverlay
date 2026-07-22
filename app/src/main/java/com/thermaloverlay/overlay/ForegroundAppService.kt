/**
 * Tracks which app is currently in the foreground, for the thread monitor
 * and framerate recorder (neither of which has any other way to know which
 * process to attach to).
 *
 * This is a deliberately simplified implementation: a full version would
 * also drive launcher detection, auto-skip-ad, input-method bookkeeping,
 * and a proper multi-window analysis pass (inspecting getWindows() to find
 * the largest/focused window, handling split-screen and floating windows
 * correctly). None of that is needed here — just "what's currently in
 * front" — so this only listens for TYPE_WINDOW_STATE_CHANGED and takes the
 * event's own package name, filtering out this app, System UI, and the
 * current input method (so opening the keyboard over an app doesn't get
 * misread as an app switch).
 * Known limitation: split-screen / picture-in-picture can report the wrong
 * side's package, since there's no window-geometry analysis here.
 */
package com.thermaloverlay.overlay

import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.thermaloverlay.overlay.data.GlobalStatus

class ForegroundAppService : android.accessibilityservice.AccessibilityService() {
    private var inputMethodPackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        inputMethodPackages = try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (ex: Exception) {
            emptySet()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return
        if (packageName == "com.android.systemui") return
        if (packageName in inputMethodPackages) return

        GlobalStatus.lastPackageName = packageName
    }

    override fun onInterrupt() {}
}
