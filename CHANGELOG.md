# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与[语义化版本](https://semver.org/lang/zh-CN/)。
每个正式版本的完整说明见 [GitHub Releases](https://github.com/lobster-xiaozhua/PaperRead/releases)。

## [未发布]

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

## [1.0.1] - 2026-10-17

首个公开发布：从工程脚手架到可导入 / 可阅读的完整闭环，并完成一轮审计修复。

### 新增
- 工程脚手架：Compose + Material 3 + 类型安全导航 + Gradle 版本目录
- 数据层：Room（书库 / 章节 / 书签 / 进度，双偏移索引）+ DataStore 设置
- TXT 导入：SAF + 流式拷贝 + SHA-256 去重 + 磁盘预检 + 编码探测（BOM → 严格 UTF-8 → GB18030 回退，结果页可切换）
- EPUB 导入：OPF / spine / NCX / DRM 检测 / GBK 条目名兜底
- 阅读器：LazyColumn 排版 / 5 套主题 / 亮度 / 常亮 / 沉浸 / 音量键翻章 / 段级+像素级进度恢复 / 目录 / 书签
- 测试与 CI：JVM 单测 + Robolectric + Compose 冒烟 + JaCoCo + GitHub Actions（测试 / lint / Debug + Release 双门禁）

### 修复
- 书签与进度一致性；导入可取消 / 可替换；像素级恢复偏差

[未发布]: https://github.com/lobster-xiaozhua/PaperRead/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/lobster-xiaozhua/PaperRead/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/lobster-xiaozhua/PaperRead/releases/tag/v1.0.1
