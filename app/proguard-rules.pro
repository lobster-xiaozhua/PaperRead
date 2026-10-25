# PaperRead ProGuard / R8 规则
# M6 启用 R8 时按需补充；当前保持默认规则即可。

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.lunalight.paperread.**$$serializer {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class io.github.lunalight.paperread.** {
    kotlinx.serialization.KSerializer serializer(...);
}
