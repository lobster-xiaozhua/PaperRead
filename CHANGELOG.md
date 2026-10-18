# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与[语义化版本](https://semver.org/lang/zh-CN/)。
每个正式版本的完整说明见 [GitHub Releases](https://github.com/lobster-xiaozhua/PaperRead/releases)。

## [未发布]

### 新增
- **关于页**：应用名与版本号、隐私承诺（完全离线 / 不申请网络权限）、Apache-2.0 许可全文、GitHub 主页与 Issue 反馈（系统浏览器打开，无内置 WebView）、无第三方依赖声明与维护者信息
- **设置页接入阅读偏好**：主题 / 字体 / 字号 / 行距 / 段距 / 页边距 / 亮度 / 屏幕常亮 / 沉浸模式 / 音量键翻章，与阅读器底部弹层共用同一套控件与数据源，实时生效并自动持久化

### 变更
- **Kotlin 工具链升级 2.0.21 → 2.2.20**（配套 KSP 2.2.20-2.0.4、Room 2.8.5）：解锁 AndroidX / kotlinx 新版依赖（其 class 文件 metadata 为 Kotlin 2.2 编译，与 2.0.21 工具链不兼容，此前 Dependabot 的 minor 更新全部飘红）。AGP 8.7.3 内置 R8 早于 Kotlin 2.2.20，release 构建存在 Kotlin 元数据解析警告（非致命，保守保留相关类），后续随 AGP 升级消解

### 基础设施
- 覆盖率门禁落地（JaCoCo 行覆盖率 ≥ 0.80，CI 强制执行）
- CI 增强：wrapper 校验、concurrency 取消、测试报告与覆盖率报告上传
- Dependabot 策略：gradle 生态仅 patch、actions 生态忽略大版本（Kotlin 锁定期收敛 churn）

## [1.1.0] - 2026-10-17

质量收敛版：流式导入、结构化失败原因、备份策略定案。

### 新增
- **大文件流式导入**：200 MB 级 TXT 常驻内存与文件大小无关（分块流式拷贝 + 双偏移懒加载）
- **导入失败原因结构化**：`ImportError` 密封类 8 个子类，携带失败阶段 / 触发值 / 用户话术，UI 直接取用、失败原因可区分
- **备份策略（方案甲-B）**：云备份排除书库目录（`books/`，规避 25 MB 云配额上限），换机（D2D）保留整库；`fullBackupContent` + `dataExtractionRules` 双格式覆盖 Android 11–12+
- **阅读器健壮性**：系统栏安全区全覆盖、单行 TXT 内联扫描切章、进度与数据边界加固
- **开源物料**：CONTRIBUTING、AGENTS 协作规则、Issue 模板、依赖清单、Keystore 生成脚本

### 变更
- 判重「替换」改为原子重解析：临时文件落库、成功才替换，失败不破坏原记录；仅当旧书源文件已丢失才回落「删记录 + 重新导入」
- CI 增加 `assembleRelease` 作为 R8 混淆门禁（此前只跑 debug，混淆规则缺失要等发版才暴露）

### 质量
- 单元测试全绿；lint 0 error / 0 warning
- 行覆盖率约 87%（JaCoCo，见下方 CI 覆盖率门禁）

[未发布]: https://github.com/lobster-xiaozhua/PaperRead/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/lobster-xiaozhua/PaperRead/compare/v1.0.1...v1.1.0
