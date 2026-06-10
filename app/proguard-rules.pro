# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview { 
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { 
  **[] $VALUES; 
  public *; 
}

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-dontwarn kotlin.reflect.**

# 保留自定义模型类
-keep class com.example.musicplayer.model.** { *; }

# 保留服务和广播接收器
-keep class com.example.musicplayer.service.** { *; }
-keep class com.example.musicplayer.receiver.** { *; }