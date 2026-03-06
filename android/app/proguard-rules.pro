# NeuralBridge ProGuard/R8 Rules

# Keep companion app classes
-keep class com.neuralbridge.companion.** { *; }
-keep interface com.neuralbridge.companion.** { *; }

# Keep Protobuf generated classes
-keep class neuralbridge.** { *; }
-dontwarn com.google.protobuf.**
-keep class com.google.protobuf.** { *; }

# Keep AccessibilityService and NotificationListenerService (system binds by name)
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.service.notification.NotificationListenerService

# Preserve source file names and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
