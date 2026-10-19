# Keep the Karoo extension surface reachable across processes via AIDL.
-keep class io.hammerhead.karooext.aidl.** { *; }
-keep class io.hammerhead.karooext.extension.** { *; }
-keep class io.hammerhead.karooext.internal.** { *; }
-keep class com.jbdbms.karoo.karoo.** { *; }
-keep class * extends android.os.Binder { *; }

# kotlinx-serialization resolves serializers reflectively through each
# @Serializable class' Companion / generated $$serializer.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class io.hammerhead.karooext.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class io.hammerhead.karooext.models.** {
    *** Companion;
}
