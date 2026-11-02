# kotlinx.serialization: keep generated serializers of the backup model in :core.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class it.spesami.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class it.spesami.core.**$$serializer { *; }
