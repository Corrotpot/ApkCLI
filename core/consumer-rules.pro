# JNI entry points are looked up by name from Rust; never rename or strip them.
-keep class dev.apkcli.core.nativebridge.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# AIDL stubs
-keep class dev.apkcli.core.ipc.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.apkcli.core.**$$serializer { *; }
-keepclassmembers class dev.apkcli.core.** {
    *** Companion;
}
-keepclasseswithmembers class dev.apkcli.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
