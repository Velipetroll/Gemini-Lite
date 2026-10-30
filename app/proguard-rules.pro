# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.google.geminiclone.GeminiNative { *; }
