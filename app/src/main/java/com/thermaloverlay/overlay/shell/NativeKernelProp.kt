/**
 * Reads a numeric sysfs value without a process: through a tiny JNI helper
 * (fopen/fscanf), or — if the native library is unavailable on this ABI — a
 * plain Kotlin file read. Used on the hot path.
 */
package com.thermaloverlay.overlay.shell

import java.io.File
import java.io.FileInputStream

class NativeKernelProp {
    // Instance method so the JNI symbol name stays
    // Java_..._NativeKernelProp_getKernelPropLong.
    external fun getKernelPropLong(path: String): Long

    companion object {
        private val nativeLoaded: Boolean = try {
            System.loadLibrary("native-lib")
            true
        } catch (t: Throwable) {
            false
        }

        private val instance: NativeKernelProp? = if (nativeLoaded) NativeKernelProp() else null

        /** First number in the file, or -1 if it can't be read/parsed. */
        fun readLong(path: String): Long {
            if (instance != null) {
                try {
                    return instance.getKernelPropLong(path)
                } catch (e: UnsatisfiedLinkError) {
                    // fall through to the Kotlin implementation
                }
            }
            return readLongFallback(path)
        }

        internal fun readLongFallback(path: String): Long {
            return try {
                val text = FileInputStream(File(path)).use { String(it.readBytes()) }
                text.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: -1L
            } catch (e: Exception) {
                -1L
            }
        }
    }
}
