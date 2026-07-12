/**
 * JNI wrapper for reading a numeric sysfs value directly via fopen/fscanf,
 * bypassing the root shell. Used on the hot path.
 */
package com.thermaloverlay.overlay.shell

class NativeKernelProp {
    external fun getKernelPropLong(path: String): Long

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
}
