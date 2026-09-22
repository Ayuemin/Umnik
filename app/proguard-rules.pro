# Umnik release R8 rules.
#
# Keep only the parts that can be reached indirectly or whose field names are part
# of persisted/network JSON contracts. Android/Jetpack consumer rules handle their
# own components.

# Kotlin/Gson rely on generic signatures and runtime annotations for some JSON paths.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Umnik stores and exchanges several model objects as JSON. Keeping the model
# package prevents R8 from renaming/removing fields that form those contracts.
-keep class com.ayuemin.ymnik.model.** { *; }

# Android creates these entry points outside normal call graphs.
-keep class com.ayuemin.ymnik.MainActivity { *; }
-keep class com.ayuemin.ymnik.RequestKeepAliveService { *; }
-keep class com.ayuemin.ymnik.RequestUidtJobService { *; }

# WorkManager may instantiate workers by class name after process recreation.
-keep class com.ayuemin.ymnik.OpenRouterBackgroundWorker { *; }
-keep class com.ayuemin.ymnik.OpenRouterJobWorker { *; }
-keep class com.ayuemin.ymnik.OpenRouterRecoveryWorker { *; }

# Preserve explicitly annotated Gson members even outside the model package.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# pdfbox-android references an optional JPEG-2000 decoder that is not bundled.
# Umnik only extracts text from PDFs; suppress the optional-class warning.
-dontwarn com.gemalto.jp2.**

# WorkManager recreates the durable knowledge indexing worker by class name.
-keep class com.ayuemin.ymnik.KnowledgeIndexWorker { *; }
