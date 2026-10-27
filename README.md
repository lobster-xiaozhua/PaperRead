# 纸阅 PaperRead

[![Android CI](https://github.com/lobster-xiaozhua/PaperRead/actions/workflows/android.yml/badge.svg)](https://github.com/lobster-xiaozhua/PaperRead/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/lobster-xiaozhua/PaperRead?label=release)](https://github.com/lobster-xiaozhua/PaperRead/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

一个**本地优先、最小权限**的开源小说阅读器（Android）：编码识别、章节切分、正文提取、语音朗读全部在本机完成；支持同一局域网内与其他纸阅设备**传书**、**换机迁移**与**阅读进度同步**。

- **最小权限**：声明项仅 INTERNET 与朗读前台服务三件套（`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `POST_NOTIFICATIONS`）——Android 无「仅局域网」权限，任何 socket（含连接局域网 IP）都必须声明 INTERNET，所有网络通信仅限同一局域网内的设备发现与书籍 / 状态传输；前台服务三件套仅用于朗读退到后台后继续播放并在通知栏控制（语音由系统 TTS 引擎在本机离线合成）。**不访问公网、不收集、不上传任何个人信息**
- **不做书源、账号、云同步**：阅读数据仅存储在本机
- **本地导入 TXT / EPUB**：智能章节解析，5 套预设阅读主题，段级进度记忆
- **零二进制素材**：封面程序化生成，图标为矢量图，安装包干净可审查

> 状态：**v1.2.0**（体验增强版：TTS 语音朗读 / 局域网传书·迁移·进度同步 / 设置页与关于页 / 仿真翻页 / 平板双页跨页，534 单测全绿 / lint 0·0 / 行覆盖率以 `jacocoTestReport` 报告为准（门禁 ≥ 80%，经登记的零覆盖纯渲染类不计入分母，清单见 `app/build.gradle.kts`））。
> `main` 含未发布变更：Kotlin 工具链升级（版本以 `gradle/libs.versions.toml` 为单一来源）、TTS 语音朗读（系统 TTS 离线朗读 + 后台常驻播放）。未来规划见 [ROADMAP.md](docs/ROADMAP.md)。

## 功能

| 模块 | 状态 |
|---|---|
| 工程脚手架（Compose + Material 3 + 类型安全导航） | ✅ |
| 本地导入 TXT（SAF + 流式拷贝 + SHA-256 去重 + 磁盘预检） | ✅ |
| EPUB 导入（OPF / spine / NCX / DRM 检测 / GBK 条目名兜底） | ✅ |
| 编码探测（BOM → 严格 UTF-8 → GB18030 回退，结果页可切换） | ✅ |
| 智能章节解析（强正则 + 目录页跳过 + 启发式 + 强制分段回退） | ✅ |
| 书架（继续阅读 / 网格 / 程序化封面 / 排序 / 多选删除 / 重新解析） | ✅ |
| 数据层（Room：书库·章节·书签·进度，双偏移索引） | ✅ |
| 阅读器（LazyColumn 排版 / 5 套主题 / 亮度 / 常亮 / 沉浸） | ✅ |
| 进度记忆恢复（ReaderPosition 段级 + 像素级） | ✅ |
| 阅读设置（字号 / 行距 / 段距 / 边距 / 音量键翻章，实时生效自动持久化） | ✅ |
| 目录 / 书签 / 重新解析章节 | ✅ |
| 大文件流式导入（200 MB 级 TXT 常驻内存与文件大小无关） | ✅ |
| 导入失败原因结构化（8 类错误、可区分话术、定位失败阶段） | ✅ |
| 备份策略（云备份排除书库 / 换机保留整库） | ✅ |
| 单元测试 + lint + release 构建 + CI 门禁 + 覆盖率门禁 | ✅ |
| 局域网传书（UDP 发现 + 局域网 HTTP：多选发送 / 进度可取消 / 复用导入流水线） | ✅ |
| 换机迁移（整库打包为单一 zip、逐本导入、进度书签 last-write-wins 回写） | ✅ |
| 进度同步（双向交换阅读位置与书签、last-write-wins 冲突、书签只增不删） | ✅ |
| TTS 语音朗读（系统 TTS 离线合成 / 段级高亮跟随 / 语速音调 / 章尾自动续读 / 后台常驻播放） | ✅ |

Roadmap：备份恢复、仿真翻页、自定义字体、平板适配、国际化（见 [ROADMAP.md](docs/ROADMAP.md)）。

## 构建

环境要求：**JDK 17** + **Android SDK 35**（Android Studio Ladybug 或更新，或命令行工具）。

```bash
# 克隆
git clone https://github.com/lobster-xiaozhua/PaperRead.git
cd PaperRead

# 本地 SDK 路径（按实际修改）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 构建两个 flavor 的 Debug APK（client 客户端正式版 + dev 调试版）
./gradlew assembleDebug

# 产物
# app/build/outputs/apk/client/debug/app-client-debug.apk   ← 客户端正式版
# app/build/outputs/apk/dev/debug/app-dev-debug.apk         ← 调试版

# 只构建单个 flavor
./gradlew assembleClientDebug

# 运行单元测试（两个 flavor 都跑）
./gradlew testClientDebugUnitTest testDevDebugUnitTest

# 静态检查
./gradlew lint

# Release 构建（R8 + 资源压缩，仅 client flavor 对外交付）
./gradlew assembleRelease
```

### 分发 flavor

| flavor | 用途 | applicationId | 备注 |
|---|---|---|---|
| `client` | 客户端正式版，唯一对外交付渠道 | `io.github.lunalight.paperread` | 覆盖率门禁绑定其测试变体 |
| `dev` | 调试版 | `io.github.lunalight.paperread.dev` | 与 client 可同机安装，便于一台设备自测传书互发；`BuildConfig.IS_DEBUG_BUILD = true` |

> 调试版 flavor 取名 `dev` 而非 `debug`：AGP 禁止 flavor 与 build type 同名。

### Release 签名（keystore 不入库）

```bash
# 1. 生成本地 keystore（默认落 ~/.paperread/，.gitignore 已屏蔽）
./scripts/generate-keystore.sh

# 2. 按脚本提示把 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD /
#    RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD 写入 local.properties

# 3. 重新构建即得已签名 APK
./gradlew assembleRelease
```

装机前请按 [docs/DEVICE_CHECKLIST.md](docs/DEVICE_CHECKLIST.md) 完成真机验证。

## 架构

四层分层，`reader/` 引擎为零 Android 依赖的纯 Kotlin 模块，全部逻辑可 JVM 单测。
分层由 **Gradle 模块边界**强制（T-010 / R-12），不只是口头约定：

```
:app          Android 应用 —— UI · ViewModel · Room · DataStore · Android 相关实现
              （FileBookTextStore / AndroidXmlParser / XmlParsers）
:core:reader  纯 JVM 阅读引擎（kotlin-jvm，api :core:domain）—— 零 Android 依赖
:core:domain  纯 JVM 领域模型与仓储接口（kotlin-jvm）
```

`:core:*` 只应用 `kotlin("jvm")`：谁在引擎或领域层写下 `import android.*`，编译立刻失败；
这两个模块的单测在纯 JVM 上跑（无 Robolectric）。三者共用同一组包名
（`io.github.lunalight.paperread.*`），所以分层调整对调用方透明。

```
UI 层       Jetpack Compose + Material 3 · ViewModel + StateFlow 单向数据流
Domain 层    纯 Kotlin 模型（Book / Chapter / Bookmark / ReaderPosition）· :core:domain
Data 层      Room（书库·章节·进度·书签）· DataStore（设置）· Repository
Reader 引擎  CharsetDetector · ChapterSplitter · EpubParser（只依赖 XmlParser 接口）· 双偏移懒加载 · :core:reader
```

关键设计：

- **双偏移体系**：Chapter 同时记录字符偏移（进度计算）与字节偏移（文件读取），大文件流式切章、按章懒加载，百 MB 小说内存占用仅为单章
- **XmlParser 接口**：Android 端包装 XmlPullParser（`:app`），JVM 端用 JDK SAX（`:core:reader` 的 `JvmXmlParser`），EPUB 核心逻辑零 Android 依赖可直测
- **字符加权进度**：`(cumulativeChars + 章内偏移) / totalChars`，章节长短不均也不跳变

详细设计见仓库内技术方案文档与 `docs/SPEC.md`、`docs/TASKS.md`。

## 开发规范

- [AGENTS.md](AGENTS.md)：AI 协作常驻规则（必读）
- [CONTRIBUTING.md](CONTRIBUTING.md)：贡献指南
- [SECURITY.md](SECURITY.md)：安全漏洞报告政策
- [CHANGELOG.md](CHANGELOG.md)：版本变更记录
- CI 门禁：七个作业独立并行，全绿方可合并 —— `unit`（双 flavor 单测 + 覆盖率门禁）、`robolectric-ui`（Robolectric UI 冒烟，失败重试 ≤ 1）、`lint`（Android lint + 文档口径一致性）、`room-migration`（Room 迁移登记 + destructive fallback 禁用）、`network-audit`（不访问公网 / 无三方网络 SDK）、`assemble`（双 flavor 装配，含 R8 release）、`r8-smoke`（R8 mapping keep 断言 + release 变体序列化 round-trip）。拆成独立作业的理由：**失败定位清晰**（哪道门禁红一目了然）+ **flaky 隔离**（Robolectric 渲染抖动被隔离在 `robolectric-ui`，不拖累 `unit`）
- 覆盖率门禁口径：行覆盖率 ≥ 80%。分母默认包含**所有**类；仅**经登记的零覆盖纯渲染类**不计入分母（当前 7 个，显式类名清单见 `app/build.gradle.kts` 的 `coverageExcludedRenderClasses`，新增需登记并说明理由——登记条件为「CI 口径下该类 0% 且无测试调用其逻辑成员」）。之所以不再用 `*ScreenKt` 之类的通配：通配会让新增的 `FooScreen.kt` 自动脱离度量、给「往 Screen.kt 里加逻辑」开后门；改为白名单后新增文件默认进分母，覆盖率掉了就会被门禁拦住。UI 行为由 `robolectric-ui` 作业的行为冒烟承载

## 发布

仓库已上线：<https://github.com/lobster-xiaozhua/PaperRead>。发版步骤：

1. 更新 `CHANGELOG.md` 与 `app/build.gradle.kts` 的 `versionName` / `versionCode`
2. 提交后打标签并推送：`git tag -a vX.Y.Z -m "..." && git push origin main vX.Y.Z`
3. 构建产物：`./gradlew assembleDebug assembleRelease`
4. 在 [Releases](https://github.com/lobster-xiaozhua/PaperRead/releases) 基于该 tag 创建 Release 并附上 APK

签名见上文「Release 签名」（keystore 不入库，经 `local.properties` 注入）。

## 依赖清单

Compose BOM、Material 3、Navigation Compose、Lifecycle、Room、DataStore、kotlinx-coroutines、kotlinx-serialization、**androidx.window（WindowSizeClass，平板适配）**（以上均为 AndroidX / JetBrains 官方库，无第三方杂项依赖）。

## 许可证

[Apache-2.0](LICENSE)
