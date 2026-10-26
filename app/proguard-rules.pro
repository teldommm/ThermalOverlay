# Release builds use R8 with proguard-android-optimize.txt, which already keeps
# `native` methods (NativeKernelProp.getKernelPropLong is called from JNI by
# name). Add project-specific keep rules below if reflection is ever introduced.
