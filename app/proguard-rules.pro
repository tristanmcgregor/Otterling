# Knox APIs are invoked reflectively so the app can build before the proprietary
# SDK JAR is downloaded from Samsung.
-keep class com.samsung.android.knox.** { *; }

# MediaPipe Tasks + bundled TFLite runtime (used for on-device habit photo-proof image
# embeddings). These load native code and JNI-bound classes reflectively, so keep them and
# suppress warnings about their optional/absent dependencies.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.protobuf.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
