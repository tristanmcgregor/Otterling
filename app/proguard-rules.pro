# Knox APIs are invoked reflectively so the app can build before the proprietary
# SDK JAR is downloaded from Samsung.
-keep class com.samsung.android.knox.** { *; }
