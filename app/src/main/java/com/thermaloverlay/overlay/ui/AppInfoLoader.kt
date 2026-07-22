@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

/**
 * Loads and caches app icons by package name, off the main thread.
 * Covers the one overload AdapterProcessMini actually calls; doesn't
 * include loading icons from an AppInfo/APK path or a combined
 * name+icon lookup, since neither is used here.
 */
package com.thermaloverlay.overlay.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

class AppInfoLoader(private val context: Context, cacheSize: Int = 100) {
    private val iconCache = LruCache<String, Drawable>(cacheSize)
    private val pm: PackageManager by lazy { context.packageManager }

    fun loadIcon(packageName: String): Deferred<Drawable?> {
        return GlobalScope.async(Dispatchers.IO) {
            iconCache.get(packageName)?.let { return@async it }

            try {
                val appInfo = pm.getPackageInfo(packageName, 0).applicationInfo
                val icon = appInfo?.loadIcon(pm)
                if (icon != null) iconCache.put(packageName, icon)
                icon
            } catch (ex: Exception) {
                null
            }
        }
    }
}
