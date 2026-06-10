# --- Глобальные настройки ---
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# --- Нативные методы ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- SQLCipher ---
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# --- WebRTC & JNI Zero ---
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-dontwarn org.webrtc.**
-dontwarn org.jni_zero.**

# --- Bitcoinj ---
-keep class org.bitcoinj.** { *; }
-dontwarn org.bitcoinj.**
-keep class com.google.common.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.google.protobuf.** { *; }

# --- Hilt / Dagger ---
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.app.Service
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep class androidx.room.Room

# --- СЕТЕВОЙ ПРОТОКОЛ (Критично для совместимости) ---
# Запрещаем переименовывать поля в классах, которые сериализуются в JSON через Gson.
# Если поля будут переименованы в "a", "b", "c", другое приложение их не поймет.

# Модели сообщений и сигналов
-keep class com.nax.atsupager.data.model.** { *; }
-keep class com.nax.atsupager.data.network.** { *; }
-keep class com.nax.atsupager.security.EncryptedPayload { *; }
-keep class com.nax.atsupager.data.network.SignalData { *; }
-keep class com.nax.atsupager.data.network.MessageWrapper { *; }

# Защита всех классов с аннотациями Gson
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Прочее ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
