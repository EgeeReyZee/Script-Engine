# Chaquopy — keep all Python bridge classes
-keep class com.scriptengine.api.** { *; }
-keep class com.scriptengine.engine.ImageFinder$Point { *; }

# AdbLib
-keep class com.tananaev.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep JNI-called methods
-keepclasseswithmembernames class * {
    native <methods>;
}
