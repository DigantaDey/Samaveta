# SAMAVETA release shrinking rules.
#
# Models and android integrations will be added here as they land. Only rules that are needed are
# present: over-broad "keep everything" rules defeat the purpose of shrinking and hide real issues.

# Hilt / Dagger generate code reflectively referenced from generated components.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembers class * { @javax.inject.Inject <init>(...); }

# Room: entities and DAOs are referenced by generated implementations.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Kotlin metadata is required for reflection over sealed hierarchies in the core module.
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

# Do not keep line numbers out of crash reports: stack traces must stay diagnosable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Coroutines
-dontwarn kotlinx.coroutines.**
