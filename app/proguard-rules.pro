# ONNX Runtime utilise JNI et recherche plusieurs classes Java par leur nom.
# Ces règles protègent aussi une future compilation release avec minification.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
