# AGENTS.md — AI 协作常驻规则

本文件对人类贡献者与 AI 编程助手同时生效。任何代码改动都必须遵守。

## 铁律

1. **任务制**：只修改当前工单（`docs/TASKS.md`）指定的文件；需要动工单外文件时，先在 PR/对话中说明理由。
2. **CI 门禁**：每次修改后必须本地执行并通过：
   ```bash
   ./gradlew testClientDebugUnitTest testDevDebugUnitTest lint assembleDebug
   ```
   全绿才算完成（flavor 拆分后两变体都跑；覆盖率门禁见下）。
3. **禁止改测试凑通过**：测试失败先修生产代码。确需修改测试时，必须说明是测试本身写错（并保留原用例意图）。
4. **依赖控制**：不新增第三方依赖。确有必要时需明确批准，并在 README 依赖清单登记。AndroidX / JetBrains 官方库优先。
5. **分层纪律**：
   - `reader/` 引擎保持零 Android 依赖（XML 解析只依赖 `XmlParser` 接口，由外层装配）；
     该纪律自 T-010 起由 Gradle 模块边界强制：`:core:reader` / `:core:domain` 是纯 JVM
     模块（只应用 `kotlin("jvm")`）；数据层（Room / DataStore / 导入流水线）自 T-013 起
     住在 `:core:data`（Android library）；Android 相关实现 `FileBookTextStore` 留在
     `:app`，`AndroidXmlParser` / `XmlParsers` 随其唯一生产消费方落 `:core:data`；
   - UI 层禁止直接访问 Room / 文件系统；
   - `data/` 实现类型不得向上泄漏。
6. **最小权限**：`AndroidManifest.xml` 只声明两组权限（2026-10-18 / 2026-10-19 用户拍板）：① `INTERNET`——仅可用于同一局域网内的设备发现与书籍 / 状态传输（传书 / 迁移 / 同步），**不得访问公网**；② 朗读前台服务三件套（`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `POST_NOTIFICATIONS`）——仅用于 TTS 朗读后台常驻播放与通知控制（语音本机离线合成，不联网）。除此之外新增任何其他权限都需单独说明理由并经人类审查。
7. **测试先行**：`reader/` 引擎的每个新逻辑先写 JVM 单测——T-010 起阅读引擎测试住在
   `core/reader/src/test/kotlin/`（fixture 放 `core/reader/src/test/resources/`），
   该模块**不得**引入 Robolectric：测试能在纯 JVM 上跑，是「引擎零 Android 依赖」的实证。

## 人工把关重点

以下类型的改动必须经人类审查通过才能合并：

- 数据模型（Room Entity / DAO / 迁移）
- IO 边界（文件读写、SAF、编码探测）
- 权限与 Manifest
- 并发（协程作用域、调度器）
- 架构分层调整

## 版本组合（勿随意升级）

工具链版本以 `gradle/libs.versions.toml` 为单一来源（AGP / Kotlin / KSP / Room / Gradle / JDK / compileSdk / minSdk）；文档不得硬编码版本号，CI 脚本 `scripts/check-doc-consistency.sh` 断言。
