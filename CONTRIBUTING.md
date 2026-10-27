# 贡献指南

感谢你愿意参与纸阅 PaperRead！

## 环境准备

1. JDK 17、Android SDK 35
2. Fork 并克隆仓库，`echo "sdk.dir=/path/to/Android/sdk" > local.properties`
3. `./gradlew assembleDebug` 确认能构建

> 国内网络环境如遇依赖下载缓慢，可在 `~/.gradle/init.gradle` 中配置镜像（阿里云等）加速，不影响仓库文件。

## 开发流程

1. 从 `main` 创建分支：`feat/xxx` 或 `fix/xxx`
2. 按 `docs/TASKS.md` 的工单执行，小步提交
3. **本地全绿再推送**：
   ```bash
   ./gradlew testClientDebugUnitTest testDevDebugUnitTest lint assembleDebug
   ./scripts/check-doc-consistency.sh
   ```
4. 提交 PR，说明改动动机与影响面

## 提交规范

Conventional Commits，中文描述，例如：

```
feat(reader): 实现阅读器底部设置面板
fix(txt): 修复 GB18030 编码误判为 UTF-8 的问题
test(epub): 补充无 NCX 目录的解析用例
```

## 代码约定

- Kotlin，`kotlin.code.style=official`
- `reader/` 引擎保持零 Android 依赖（纯 Kotlin，可 JVM 单测）——T-010 起由独立 Gradle 模块
  `:core:reader` 编译期强制：该模块只应用 `kotlin("jvm")`，一旦出现 `import android.*` 直接编译失败
- UI 层不直接访问 Room / 文件系统，一律经 Repository
- **逻辑不写进 `*Screen.kt`**：页面文件只放 `@Composable` 渲染树，任何可测的状态 / 计算
  （分页、排序、格式化以外的业务判断、状态机）放独立文件（`XxxViewModel` / `XxxManager` /
  纯函数文件）。**写进去也会被度量**——覆盖率分母默认包含所有类，只有 `app/build.gradle.kts`
  里显式登记的「零覆盖纯渲染类」才划出分母；因此往 `*Screen.kt` 里塞逻辑不会自动脱离度量，
  反而会让该文件因含逻辑而需要退出白名单（清单需显式登记并说明理由）
- 新增第三方依赖需在 PR 中说明理由并在 README 依赖清单登记
- **最小权限**：`AndroidManifest.xml` 仅声明以下权限（新增任何权限需单独评审，
  并同步本清单与 README 表述；CI 脚本 `scripts/check-doc-consistency.sh`
  会逐一比对一致性）：
  - `android.permission.INTERNET`：仅用于同一局域网内的设备发现与书籍 / 状态传输，不访问公网；
  - `android.permission.FOREGROUND_SERVICE` / `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `android.permission.POST_NOTIFICATIONS`：朗读退到后台后继续播放并在通知栏控制。

## 文档口径（CI 强制）

`scripts/check-doc-consistency.sh`（CI `lint` 作业执行）五项断言：

1. 权限清单：`CONTRIBUTING.md` 声明的权限与 `AndroidManifest.xml` 实际声明逐一一致；
2. 覆盖率：文档不写死行覆盖率实测数字，统一以 `jacocoTestReport` 实际 XML 报告为准（门禁阈值除外）；门禁口径为「行覆盖率 ≥ 80%」——分母默认包含所有类，仅 `app/build.gradle.kts` 中 `coverageExcludedRenderClasses` 显式登记的「经核实 CI 口径 0% 且无测试调用其逻辑成员」的零覆盖纯渲染类不计入分母（当前 7 个；新增需登记并说明理由），UI 行为由 `robolectric-ui` 作业的冒烟承载；
3. Kotlin 版本：文档不硬编码版本号，单一来源为 `gradle/libs.versions.toml`；
4. 单测总数：`README.md` / `docs/ROADMAP.md` / `docs/TASKS.md` 质量基线中的「N 单测」必须等于**全部测试源码目录**（`app/src/test` + `core/*/src/test`）下 `@Test` 用例数（代码为单一来源，写精确数字，不写「约」「N+」）；
5. CI 作业数：文档中「N 门禁 / N 个作业」必须等于 `.github/workflows/android.yml` 的作业数，且每个作业名都要在 `README.md` 以反引号形式登记。

## 人工审查重点

数据模型、IO 边界、权限、并发、架构分层的改动需要重点审查，PR 中请主动说明。
