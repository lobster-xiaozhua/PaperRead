# PaperRead ProGuard / R8 规则
#
# 变更纪律（T-008 · R-07 / G7）：本文件的**规则**保持零改动回归——release
# 变体的可用性由两道自动化断言守着：
#   1. `scripts/check-r8-keep.sh`：assembleClientRelease 后断言 mapping.txt 中
#      序列化契约类型、生成的 `$$serializer`、ImportError 及其子类均保留在产物里；
#   2. `testClientReleaseUnitTest` 下的 `quality.ReleaseSerializationTest`：在
#      release 变体上对全部序列化对象做 JSON round-trip。
# 新增 **R8 需要特殊照顾** 的反射/序列化用法时，才允许在此加规则，并在 PR 说明
# 「不加会怎样」，同时把对应类型补进 check-r8-keep.sh（否则断言覆盖不到）。
#
# 关于「为什么这些规则够用」：
# - kotlinx.serialization 的编解码**不走反射**——编译器插件为每个 @Serializable
#   类生成 `Foo$$serializer`，`Foo.serializer()` 直接返回该单例。R8 需要保住的
#   只有三样：模型类本身（含其 `serializer()`）、`$$serializer` 类、以及
#   `$$serializer` 的 `Companion.serializer(...)` 入口（@Serializable 的
#   `companion object` 里由插件生成的那个方法）。
# - 下方三条规则正好覆盖这三样，因此**无需**对每个模型类写逐条 `-keep`。
#   逐条枚举的坏处是新增模型时容易漏（漏了只在混淆产物上崩，debug 全绿）。
# - kotlinx-serialization-core 自身还携带 consumer ProGuard 规则（jar 内
#   META-INF/proguard/），与下方规则叠加生效。
#
# 明确的能力边界：本文件 + 上述两道断言**不能**证明「混淆后的真实运行时行为」，
# 因为混淆只在 assemble*Release 产物里发生，而单元测试跑的是未混淆的类。
# 该缺口由真机 install smoke 覆盖（CI 步骤以注释形式预留，需 KVM runner；
# 真机口径见 docs/DEVICE_CHECKLIST.md）。

# kotlinx.serialization：保留注解与内部类属性
# （@Serializable 生成的描述符依赖 InnerClasses / Annotation 信息；缺失时
#  序列化器在运行期取不到泛型参数，表现为 SerializationException）
-keepattributes *Annotation*, InnerClasses

# kotlinx.serialization：不提示其内部实现细节（该类库大量使用 @PublishedApi 内部 API，
# 提示会淹没真正需要关注的警告）
-dontnote kotlinx.serialization.**

# 生成序列化器的伴生入口：`Foo$$serializer.Companion.serializer(...)` 是
# `Foo.serializer()` 的实际实现，被裁掉则 `serializer()` 返回 null / 抛异常
-keepclassmembers class io.github.lunalight.paperread.**$$serializer {
    *** Companion;
    *** serializer(...);
}

# 模型类上的 `serializer()`：保留类型本身与该方法（含继承来的 Companion 成员）
-keepclasseswithmembers class io.github.lunalight.paperread.** {
    kotlinx.serialization.KSerializer serializer(...);
}
