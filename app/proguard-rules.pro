# Keep UpdateChecker and its data classes for Gson
-keep class com.ipdial.util.UpdateChecker** { *; }
-keep class com.ipdial.util.UpdateChecker$GitHubRelease { *; }

# Keep PJSIP JNI bridge
-keep class org.pjsip.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep targeted SIP components & callbacks
-keep class com.ipdial.service.SipEngine { *; }
-keep class com.ipdial.service.SipAccountDelegate { *; }
-keep class com.ipdial.service.SipCallDelegate { *; }
-keep class com.ipdial.service.SipService { *; }
-keep class com.ipdial.service.SipConnectionService { *; }
-keep class com.ipdial.service.BootReceiver { *; }

# Keep data models & Room entities for Gson / Room
-keep class com.ipdial.data.model.** { *; }
-keep class com.ipdial.data.local.** { *; }

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase
-keep class * implements androidx.room.RoomOpenHelper$Delegate

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.material.ripple.** { *; }
-keep class androidx.compose.material3.ripple.** { *; }
-keep interface androidx.compose.foundation.IndicationNodeFactory { *; }
