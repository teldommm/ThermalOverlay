// JNI implementation backing NativeKernelProp: reads a numeric sysfs value
// directly via fopen/fscanf, bypassing the root shell. Used on the hot path
// (CpuFrequencyUtils.getClusterFrequencyKHz).

#include <jni.h>
#include <cstdio>

extern "C" JNIEXPORT jlong JNICALL
Java_com_thermaloverlay_overlay_shell_NativeKernelProp_getKernelPropLong(
        JNIEnv *env,
        jobject /* thiz */,
        jstring path) {
    if (path == nullptr) {
        return -1;
    }

    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return -1;  // OutOfMemoryError is already pending
    }
    FILE *kernelProp = fopen(chars, "re");
    env->ReleaseStringUTFChars(path, chars);
    if (kernelProp == nullptr) {
        return -1;
    }

    long long value = -1;
    if (fscanf(kernelProp, "%lld", &value) != 1) {
        value = -1;
    }
    fclose(kernelProp);
    return static_cast<jlong>(value);
}
