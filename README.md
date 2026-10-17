# 纸阅 PaperRead

[![Android CI](https://github.com/lobster-xiaozhua/PaperRead/actions/workflows/android.yml/badge.svg)](https://github.com/lobster-xiaozhua/PaperRead/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/lobster-xiaozhua/PaperRead?label=release)](https://github.com/lobster-xiaozhua/PaperRead/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

一个**完全离线、零网络权限**的开源本地小说阅读器（Android）。

- **不申请 INTERNET 权限**：应用没有任何网络行为，隐私可审计
- **不做书源、账号、云同步**：编码识别、章节切分、正文提取全部在本机完成
- **本地导入 TXT / EPUB**：智能章节解析，5 套预设阅读主题，段级进度记忆
- **零二进制素材**：封面程序化生成，图标为矢量图，安装包干净可审查

> 状态：v1.1.0（质量收敛版：流式导入、结构化失败原因、备份策略定案）。功能 roadmap 见下文。

## 功能

| 模块 | 状态 |
|---|---|
| 工程脚手架（Compose + Material 3 + 类型安全导航） | ✅ M1 |
| 本地导入 TXT（SAF + 流式拷贝 + SHA-256 去重 + 磁盘预检） | ✅ M2 |
| EPUB 导入（OPF / spine / NCX / DRM 检测 / GBK 条目名兜底） | ✅ M3 |
| 编码探测（BOM → 严格 UTF-8 → GB18030 回退，结果页可切换） | ✅ M2 |
| 智能章节解析（强正则 + 目录页跳过 + 激进启发式 + 强制分段回退） | ✅ M2 |
| 书架（继续阅读 / 网格 / 程序化封面 / 排序 / 多选删除 / 重新解析） | ✅ M2 |
| 数据层（Room：书库·章节·书签·进度，双偏移索引） | ✅ M2 |
| 阅读器（LazyColumn 排版 / 5 套主题 / 亮度 / 常亮 / 沉浸） | ✅ M4 |
| 进度记忆恢复（ReaderPosition 段级 + 像素级） | ✅ M4 |
| 阅读设置（字号 / 行距 / 段距 / 边距 / 音量键翻章，实时生效自动持久化） | ✅ M4 |
| 目录 / 书签 / 重新解析章节 | ✅ M5 |
| 大文件流式导入（200 MB 级 TXT 常驻内存与文件大小无关） | ✅ v1.1 |
| 导入失败原因结构化（8 类错误、可区分话术、定位失败阶段） | ✅ v1.1 |
| 备份策略（云备份排除书库 / 换机保留整库） | ✅ v1.1 |
| 单元测试 + lint + release 构建 + CI 门禁 | ✅ M6 |

Roadmap：TTS 语音朗读、Wi-Fi 传书、备份恢复、仿真翻页、自定义字体、平板适配、国际化。

## 构建

环境要求：**JDK 17** + **Android SDK 35**（Android Studio Ladybug 或更新，或命令行工具）。

```bash
# 克隆
git clone https://github.com/lobster-xiaozhua/PaperRead.git
cd PaperRead

# 本地 SDK 路径（按实际修改）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 构建 Debug APK
./gradlew assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk

# 运行单元测试
./gradlew testDebugUnitTest

# 静态检查
./gradlew lint

# Release 构建（R8 + 资源压缩，未签名产物为 app-release-unsigned.apk）
./gradlew assembleRelease
```

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

四层分层，`reader/` 引擎为零 Android 依赖的纯 Kotlin 模块，全部逻辑可 JVM 单测：

```
UI 层       Jetpack Compose + Material 3 · ViewModel + StateFlow 单向数据流
Domain 层    纯 Kotlin 模型（Book / Chapter / Bookmark / ReaderPosition）
Data 层      Room（书库·章节·进度·书签）· DataStore（设置）· Repository
Reader 引擎  CharsetDetector · ChapterSplitter · EpubParser（只依赖 XmlParser 接口）· 双偏移懒加载
```

关键设计：

- **双偏移体系**：Chapter 同时记录字符偏移（进度计算）与字节偏移（文件读取），大文件流式切章、按章懒加载，百 MB 小说内存占用仅为单章
- **XmlParser 接口**：Android 端包装 XmlPullParser，JVM 测试端用 JDK SAX，EPUB 核心逻辑零 Android 依赖可直测
- **字符加权进度**：`(cumulativeChars + 章内偏移) / totalChars`，章节长短不均也不跳变

详细设计见仓库内技术方案文档与 `docs/SPEC.md`、`docs/TASKS.md`。

## 开发规范

- [AGENTS.md](AGENTS.md)：AI 协作常驻规则（必读）
- [CONTRIBUTING.md](CONTRIBUTING.md)：贡献指南
- [SECURITY.md](SECURITY.md)：安全漏洞报告政策
- [CHANGELOG.md](CHANGELOG.md)：版本变更记录
- CI 门禁：`testDebugUnitTest` + 覆盖率门禁（行覆盖 ≥ 80%）+ `lint` + `assembleDebug` + `assembleRelease`（R8）全绿方可合并

## 发布

仓库已上线：<https://github.com/lobster-xiaozhua/PaperRead>。发版步骤：

1. 更新 `CHANGELOG.md` 与 `app/build.gradle.kts` 的 `versionName` / `versionCode`
2. 提交后打标签并推送：`git tag -a vX.Y.Z -m "..." && git push origin main vX.Y.Z`
3. 构建产物：`./gradlew assembleDebug assembleRelease`
4. 在 [Releases](https://github.com/lobster-xiaozhua/PaperRead/releases) 基于该 tag 创建 Release 并附上 APK

签名见上文「Release 签名」（keystore 不入库，经 `local.properties` 注入）。

## 依赖清单

Compose BOM、Material 3、Navigation Compose、Lifecycle、Room、DataStore、kotlinx-coroutines、kotlinx-serialization（以上均为 AndroidX / JetBrains 官方库，无第三方杂项依赖）。

## 许可证

[Apache-2.0](LICENSE)
