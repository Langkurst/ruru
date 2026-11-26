# ProGuard rules for the release desktop app (Compose's own default rules stay on as well).
# Shrinking only: nothing is renamed (obfuscate is off in build.gradle.kts).

# Ruru's own code: the Windows Bluetooth calls go through JNA structures and callbacks, which JNA
# reads by reflection, and the hardware check is started by name. Nothing here may be removed.
-keep class io.github.langkurst.ruru.** { *; }

# JNA looks up fields, methods and native entry points by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# kotlinx.coroutines finds the Swing main dispatcher through a service file.
-keep class kotlinx.coroutines.swing.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler

# kotlinx.serialization (EQ presets, mode names): the generated serializers are found by name.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.langkurst.ruru.**$$serializer { *; }

# Linux Bluetooth talks to BlueZ over D-Bus with dbus-java and junixsocket (platform/linux). dbus-java reads the
# interface classes by reflection and finds its transport through service files; junixsocket's JNI names must stay.
-keep class org.freedesktop.dbus.** { *; }
-keep class org.newsclub.net.unix.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.freedesktop.dbus.**
-dontwarn org.newsclub.**
-dontwarn org.slf4j.**
-dontwarn com.kohlschutter.**
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn jnr.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
