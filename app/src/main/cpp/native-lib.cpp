// JNI implementation backing NativeKernelProp: reads a numeric sysfs value
// directly via fopen/fscanf, bypassing the root shell. Used on the hot path
// (CpuFrequencyUtils.getCurrentFrequency).

#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>

char* jstringToChar(JNIEnv* env, jstring jstr) {
    char* rtn = nullptr;
    jclass clsstring = env->FindClass("java/lang/String");
    jstring strencode = env->NewStringUTF("GB2312");
    jmethodID mid = env->GetMethodID(clsstring, "getBytes", "(Ljava/lang/String;)[B");
    auto barr = (jbyteArray) env->CallObjectMethod(jstr, mid, strencode);
    jsize alen = env->GetArrayLength(barr);
    jbyte* ba = env->GetByteArrayElements(barr, JNI_FALSE);
    if (alen > 0) {
        rtn = (char*) malloc(alen + 1);
        memcpy(rtn, ba, alen);
        rtn[alen] = 0;
    }
    env->ReleaseByteArrayElements(barr, ba, 0);
    return rtn;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_thermaloverlay_overlay_shell_NativeKernelProp_getKernelPropLong(
        JNIEnv *env,
        jobject,
        jstring path) {
    char* charData = jstringToChar(env, path);
    if (charData == nullptr) {
        return -1;
    }

    FILE *kernelProp = fopen(charData, "r");
    free(charData);
    if (kernelProp == nullptr) {
        return -1;
    }

    long freq = -1;
    if (fscanf(kernelProp, "%ld", &freq) != 1) {
        freq = -1;
    }
    fclose(kernelProp);
    return freq;
}
