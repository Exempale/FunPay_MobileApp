# ──────────────────────────────────────────────────────────────
# ProGuard / R8 rules для FunPay App
# ──────────────────────────────────────────────────────────────

# Сохраняем имена классов для отладки стэк-трейсов
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── WebView & JavaScript Interface ───────────────────────────
# Не обфусцировать классы, используемые через @JavascriptInterface
-keepattributes *Annotation*
-keepattributes JavascriptInterface
-keep public class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ─── WorkManager ──────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ─── Kotlin Coroutines ────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ─── Kotlin Serialization ─────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ─── AndroidX ─────────────────────────────────────────────────
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# ─── Material Components ──────────────────────────────────────
-keep class com.google.android.material.** { *; }

# ─── Application классы ───────────────────────────────────────
-keep class com.funpay.app.** { *; }

# ─── Убрать логи в release-сборке ────────────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# ─── Общие правила ────────────────────────────────────────────
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn okhttp3.**
-dontwarn okio.**
