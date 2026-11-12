# MaY_Ai ProGuard / R8 rules

# Keep Room entities and DAOs
-keep class id.mayai.assistant.ChatMessage { *; }
-keep class id.mayai.assistant.ChatSession { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# DataStore
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# App entry
-keep class id.mayai.assistant.MainActivity { *; }
-keep class id.mayai.assistant.ChatRepository { *; }
-keep class id.mayai.assistant.ChatApi { *; }
-keep class id.mayai.assistant.ChatViewModel { *; }

# Strip verbose logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
