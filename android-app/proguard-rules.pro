# R8 rules for release builds of the Android app.

# The AVF API is compile-only here (vmhost-avf-stubs): the classes come from the device's
# framework at runtime, so R8 can't see them. Code that uses them is reached only after the
# capability checks and the hidden-API exemption (vmhost-real HiddenApi).
-dontwarn android.system.virtualmachine.**

# The JNI broker looks these up by class and method name (azula-cli android-host-jni).
-keep class app.azula.vmhost.** { *; }
