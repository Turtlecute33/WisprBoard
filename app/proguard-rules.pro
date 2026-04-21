# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# Tink (pulled in by androidx.security:security-crypto) references compile-only
# annotations that are not on the runtime classpath. These are safe to ignore.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.concurrent.**
-dontwarn com.google.j2objc.annotations.**
