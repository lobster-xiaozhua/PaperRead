# PaperRead 开发工单（TASKS）

> 每个工单 ≤ 4 小时。退出标准：可运行 APK + `./gradlew testDebugUnitTest lint assembleDebug` 全绿。
> 规则见 AGENTS.md；验收标准见 docs/SPEC.md。

## M1 脚手架（本轮）

### T1.1 构建环境
- 涉及：JDK 17、Android SDK 35（platform-tools / platforms;android-35 / build-tools;35.0.0）、Gradle 8.9
- 验收：`sdkmanager --list_installed` 含 android-35；`gradle -v` 为 8.9

### T1.2 工程脚手架
- 涉及：settings/build/gradle.properties、libs.versions.toml、app/build.gradle.kts、AndroidManifest、主题、类型安全导航、AppContainer、三个空页面、SmokeTest
- 验收：`assembleDebug` 产出 APK；`testDebugUnitTest` 通过；APK 可安装启动（无 INTERNET 权限）

### T1.3 开源物料
- 涉及：README、LICENSE（Apache-2.0）、CONTRIBUTING、AGENTS、.github/workflows/android.yml、docs/SPEC.md、本文件
- 验收：clone 后按 README 可构建；CI 三项门禁就位

## M2 导入 + TXT + 数据层 + 书架

### T2.1 数据层（Room）
- 涉及：domain/model（Book/Chapter/Bookmark/ReaderPosition）、data/local（Entity/DAO/AppDatabase）、AppContainer 装配
- 验收：Robolectric 单测覆盖增删查、级联删除、进度写入

### T2.2 编码探测 + TXT 切分器
- 涉及：reader/txt（CharsetDetector、ChapterSplitter、ChapterIndexWriter）
- 验收：编码 6+ 用例、切分 10+ 用例（含回退/目录页跳过）全绿；双偏移 round-trip 用例通过

### T2.3 导入流水线
- 涉及：SAF 选文件、流式拷贝 + SHA-256、磁盘预检、去重、进度、导入结果页（编码可切换）
- 验收：SPEC AC-1.x / AC-2.x 通过；fixture：GBK 小说可导入不乱码

### T2.4 书架
- 涉及：ui/bookshelf（网格、程序化封面、进度、排序、多选删除、继续阅读、重新解析入口）、BookshelfViewModel
- 验收：SPEC AC-3.x 通过

## M3 EPUB 引擎

### T3.1 XmlParser 接口 + 双实现
- 涉及：reader/xml（XmlParser、AndroidXmlParser、JvmXmlParser）
- 验收：JVM 单测用 JvmXmlParser 驱动，无需 Android 环境

### T3.2 EpubParser
- 涉及：reader/epub（OPF/spine/NCX/encryption 检测/ZIP 条目名编码/相对路径）
- 验收：fixture 单测：正常 / 无 NCX / 加密 / 多级目录 / GBK 条目名

### T3.3 XHTML 正文提取 + 接入导入
- 涉及：XhtmlTextExtractor、导入分发
- 验收：EPUB 可导入、章节顺序与 spine 一致

## M4 阅读器 + 设置

### T4.1 阅读器骨架
- 涉及：ui/reader（LazyColumn 段落渲染、工具栏、切章、音量键默认关）
- 验收：SPEC AC-4.1 / AC-4.5 / AC-4.6 通过

### T4.2 ReaderPosition 记忆与恢复
- 涉及：ReaderViewModel、BookRepository
- 验收：SPEC AC-5.x 通过；恢复单测覆盖

### T4.3 设置系统
- 涉及：SettingsStore（DataStore）、设置面板（主题/字体/行距/段距/边距/亮度/常亮/沉浸）
- 验收：SPEC AC-4.2 / AC-4.3 / AC-4.4 通过

## M5 目录 / 书签 / 收尾功能

### T5.1 目录弹层 · T5.2 书签 · T5.3 重新解析章节
- 验收：SPEC AC-6.x 通过

## M6 测试 / 构建 / 交付

### T6.1 全量单测 + fixture 补全
### T6.2 lint 清零 + release 构建配置（R8、签名脚本，keystore 不入库）
### T6.3 装机验证清单 + 最终 APK + GitHub 仓库创建推送（默认 public）
- 验收：交付 源码 + APK + 文档 + 装机验证清单；CI 三项全绿
