# WorkManager
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>(...);
}
-keep class androidx.work.impl.* { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Retrofit/GSON Models
-keep class com.bartixxx.oneplusarbchecker.** { *; }
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes *Annotation*
-dontwarn retrofit2.**