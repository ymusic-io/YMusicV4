# QuickJS JNI bridge: quickjs_jni.c resolves the class and these members BY NAME
# (FindClass / GetStaticMethodID / the exception ctor) — renaming any of them breaks
# every engine callback at runtime.
-keep class io.ymusic.quickjs.QuickJsNative { *; }
-keep class io.ymusic.quickjs.QuickJsException { <init>(java.lang.String); }

# mse_host_jni.c FindClass / INSTANCE / fire — C OnEnded hops here.
-keep class io.ymusic.mse.MseJsEventBridge { *; }
-keep class io.ymusic.mse.MseLogBridge { *; }

# WebView page JS calls bridge methods through addJavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization: plugin-generated serializers are looked up via the Companion.
# The library ships consumer rules, but the named-companion lookup needs the app side too.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keep,allowshrinking,allowobfuscation class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Sentry events must map back to source lines; the file name is renamed, not dropped.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Sentry titles and fingerprints events by the client-side exception type, and the telemetry
# beforeSend matches TrailMark by name: unrenamed, and pinned against R8 class merging folding
# unrelated exceptions into one type. Unused throwables are still removed.
-keepnames class * extends java.lang.Throwable
