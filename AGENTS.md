# AGENTS.md — AI 协作常驻规则

本文件对人类贡献者与 AI 编程助手同时生效。任何代码改动都必须遵守。

## 铁律

1. **任务制**：只修改当前工单（`docs/TASKS.md`）指定的文件；需要动工单外文件时，先在 PR/对话中说明理由。
2. **CI 门禁**：每次修改后必须本地执行并通过：
   ```bash
   ./gradlew testDebugUnitTest lint assembleDebug
   ```
   三项全绿才算完成。
3. **禁止改测试凑通过**：测试失败先修生产代码。确需修改测试时，必须说明是测试本身写错（并保留原用例意图）。
4. **依赖控制**：不新增第三方依赖。确有必要时需明确批准，并在 README 依赖清单登记。AndroidX / JetBrains 官方库优先。
5. **分层纪律**：
   - `reader/` 引擎保持零 Android 依赖（XML 解析只依赖 `XmlParser` 接口，由外层装配）；
   - UI 层禁止直接访问 Room / 文件系统；
   - `data/` 实现类型不得向上泄漏。
6. **隐私承诺**：`AndroidManifest.xml` 永不添加 `INTERNET` 权限（v1）。新增任何权限都需单独说明理由。
7. **测试先行**：`reader/` 引擎的每个新逻辑先写 JVM 单测（fixture 放 `app/src/test/resources/`）。

## 人工把关重点

以下类型的改动必须经人类审查通过才能合并：

- 数据模型（Room Entity / DAO / 迁移）
- IO 边界（文件读写、SAF、编码探测）
- 权限与 Manifest
- 并发（协程作用域、调度器）
- 架构分层调整

## 版本组合（勿随意升级）

AGP 8.7.3 · Gradle 8.9 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · JDK 17 · compileSdk 35 / minSdk 26。
