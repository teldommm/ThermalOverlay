/**
 * One recorded framerate session, as listed in the history screen.
 * appName/appIcon are resolved lazily (PackageManager lookup) after loading
 * from the store, since the DB only has the package name.
 */
package com.thermaloverlay.overlay.model

import android.graphics.drawable.Drawable

class FpsWatchSession(
    val sessionId: Long,
    val packageName: String,
    val beginTime: Long
) {
    var appName: String = packageName
    var appIcon: Drawable? = null
}
