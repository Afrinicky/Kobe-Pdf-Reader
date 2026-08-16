# Kobe Reader R8 configuration.
#
# AndroidX, Hilt, Compose, Room 3 and Coil all ship consumer rules, so nothing is
# needed for them here. What follows covers the three libraries that don't:
# PdfBox-Android, BouncyCastle (its transitive crypto provider), and the
# kotlinx.serialization models used for type-safe navigation routes.

# ---------------------------------------------------------------- kotlinx.serialization
# Keep the generated serializers for our @Serializable navigation routes and
# settings models. Without this, route arguments silently fail to restore.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.kobe.reader.** {
    *** Companion;
}
-keepclasseswithmembers class com.kobe.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.kobe.reader.**
-keep, allowobfuscation, allowoptimization class <1> {
    static <1>$Companion Companion;
}

# ---------------------------------------------------------------- PdfBox-Android
# PdfBox loads COS filters, font mappers and the encryption security handlers
# reflectively from META-INF/services, so their constructors must survive.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
# PdfBox references a handful of desktop-only javax/AWT types it never calls on
# Android. Silence them rather than dragging in stubs.
-dontwarn javax.imageio.**
-dontwarn java.awt.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**

# ---------------------------------------------------------------- BouncyCastle
# Pulled in transitively by PdfBox for AES/RC4 PDF encryption. Only the JCE
# provider entry points need keeping; the rest can shrink.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---------------------------------------------------------------- Play Billing / Ads
# Both ship consumer rules; these only silence optional-dependency warnings.
-dontwarn com.android.billingclient.**
-dontwarn com.google.android.gms.**

# ---------------------------------------------------------------- Diagnostics
# Keep line numbers so Play Console crash reports stay readable after mapping
# upload, but hide the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
