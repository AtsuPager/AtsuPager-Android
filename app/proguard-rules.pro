# --- Global Settings ---
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# --- Native Methods ---
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

# --- NETWORK PROTOCOL (Critical for compatibility) ---
# Prevent renaming fields in classes that are serialized to JSON via Gson.
# If fields are renamed to "a", "b", "c", other applications won't understand them.

# Message and Signal models
-keep class com.nax.atsupager.data.model.** { *; }
-keep class com.nax.atsupager.data.network.** { *; }
-keep class com.nax.atsupager.security.EncryptedPayload { *; }
-keep class com.nax.atsupager.data.network.SignalData { *; }
-keep class com.nax.atsupager.data.network.MessageWrapper { *; }

# Keep User model for correct GSON operation
-keep class com.nax.atsupager.data.model.User { *; }
-keepattributes Signature
-keepattributes *Annotation*

# If you use TypeToken, you need to keep its internals
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Protection for all classes with Gson annotations
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Other ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
