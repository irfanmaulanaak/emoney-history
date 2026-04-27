# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# iText ProGuard rules
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# Keep NFC classes
-keep class android.nfc.** { *; }

# Keep Parcelable classes
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Keep Kotlin data classes
-keepclassmembers class **$WhenMappings {
    <fields>;
}
