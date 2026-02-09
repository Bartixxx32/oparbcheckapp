# WorkManager
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>(...);
}
-keep class androidx.work.impl.* { *; }

# Room
# RESET - Nuclear option for Data/API
-keep class com.bartixxx.oneplusarbchecker.data.** { *; }
-keepclassmembers class com.bartixxx.oneplusarbchecker.data.** { *; }
-keep interface com.bartixxx.oneplusarbchecker.data.** { *; }

# CRITICAL for Kotlin + Retrofit
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes ParameterizedType
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile
-keepattributes LineNumberTable
-keepattributes *Annotation*
-keepattributes KotlinMetadata

# Keep Kotlin Metadata class itself
-keep class kotlin.Metadata { *; }

# Gson & Retrofit internals
-keep class com.google.gson.** { *; }
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Dont warn
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement