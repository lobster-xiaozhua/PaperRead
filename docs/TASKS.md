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

## M2 导入 + TXT + 数据层 + 书架（已完成）

### T2.1 数据层（Room）
- 涉及：domain/model（Book/Chapter/Bookmark/ReaderPosition）、data/local（Entity/DAO/AppDatabase）、AppContainer 装配
- 验收：Robolectric 单测覆盖增删查、级联删除、进度写入 ✅（BookRepositoryTest 7 用例）

### T2.2 编码探测 + TXT 切分器
- 涉及：reader/txt（CharsetDetector、ChapterSplitter、ChapterIndexWriter）
- 验收：编码 10 用例、切分 12 用例（含回退/目录页跳过/重复标题终止目录链）全绿；双偏移 round-trip 用例通过 ✅

### T2.3 导入流水线
- 涉及：SAF 选文件、流式拷贝 + SHA-256、磁盘预检、去重、进度、导入结果页（编码可切换）
- 验收：SPEC AC-1.x / AC-2.x 通过；fixture：GBK 小说可导入不乱码 ✅（BookImporterTest 9 用例，含 e2e GBK 导入、判重、EPUB 拒绝、取消清理、重新解析）

### T2.4 书架
- 涉及：ui/bookshelf（网格、程序化封面、进度、排序、多选删除、继续阅读、重新解析入口）、BookshelfViewModel
- 验收：SPEC AC-3.x 通过 ✅（导入进度 / 结果确认对话框 / 长按多选删除 / 四种排序）

### 遗留说明
- 目录页与正文首章标题无空行分隔时，靠「重复标题」启发式终止目录链；极端重复场景首个标题仍可能归入目录块（文本不丢失），可在结果页切换「激进」策略重解析
- EPUB 走 Unsupported 分支，M3 接入 EpubParser 后替换
- book.txt 全文一次性解码进内存，百 MB 级超大文件需流式解码（M6 前评估）

## M3 EPUB 引擎（已完成）

### T3.1 XmlParser 接口 + 双实现
- 涉及：reader/xml（XmlParser/XmlEvent 拉取模型、AndroidXmlParser 包装 XmlPullParser、JvmXmlParser 用 JDK SAX、XmlParsers 工厂）
- 验收：JVM 单测用 JvmXmlParser 驱动 ✅（JvmXmlParserTest 5 用例 + AndroidXmlParserTest 3 用例 Robolectric 双端一致性）

### T3.2 EpubParser
- 涉及：reader/epub（container→OPF→spine→NCX、encryption DRM 检测、ZIP 条目名编码兜底、相对路径/fragment 归一化）
- 验收：fixture 单测：正常 / 无 NCX / 加密 / 多级目录 / GBK 条目名 ✅（EpubParserTest 8 用例）

### T3.3 XHTML 正文提取 + 接入导入
- 涉及：XhtmlTextExtractor（块级元素/script 跳过/实体解码）、BookImporter EPUB 分支、结果页按格式隐藏编码/策略切换
- 验收：EPUB 可导入、章节顺序与 spine 一致 ✅（BookImporterTest EPUB e2e 2 用例，含 DRM 拒绝）

### 关键实现说明
- **GBK 条目名兜底**：JDK 的 `ZipFile(UTF_8)` 在构造期即以严格 UTF-8 校验全部条目名，任一非 UTF-8 名整体抛 ZipException；因此采用「UTF-8 打开失败 → GB18030 重开」策略（带 UTF-8 标志位的条目 JDK 仍按 UTF-8 解释，混合 ZIP 正确）
- **DRM 判定**：encryption.xml 存在 EncryptedData 即拒绝（正文条目加密才可能来自真实 DRM；字体/图片加密场景留待 M6 细化）
- **章节区间**：章间分隔符归入前一章区间，保证 refs 连续覆盖全文（ChapterIndexWriter 前置条件）
- EPUB 标题/作者来自 OPF metadata；TXT 作者恒为「未知」（正文作者行解析留待 M5）

## M4 阅读器 + 设置（已完成）

### T4.1 阅读器骨架
- 涉及：ui/reader（LazyColumn 段落渲染、工具栏显隐、切章、音量键默认关）
- 验收：SPEC AC-4.1 / AC-4.5 / AC-4.6 通过 ✅

### T4.2 ReaderPosition 记忆与恢复
- 涉及：ReaderViewModel、BookRepository、ReaderProgress 纯函数
- 验收：SPEC AC-5.x 通过 ✅（ReaderProgressTest 10 用例：段起点表 / 章内偏移 / 字符加权百分比 / 夹取；防抖保存 500ms + 切章立即落库）

### T4.3 设置系统
- 涉及：SettingsStore（DataStore）、设置面板（主题/字体/行距/段距/边距/亮度/常亮/沉浸/音量键）
- 验收：SPEC AC-4.2 / AC-4.3 / AC-4.4 通过 ✅（SettingsStoreTest 4 用例 Robolectric：默认值 / 持久化 / transform 累计 / 亮度互转）

### 关键实现说明
- **Compose 1.7 API 变迁**：`Modifier.focusable` 已从 `androidx.compose.ui.focus` 移至 `androidx.compose.foundation`；material-icons-core 仅含少量图标，翻章箭头用 `Icons.AutoMirrored.Filled.KeyboardArrowLeft/Right`、设置用 `Icons.Filled.Settings`
- **进度保存**：snapshotFlow 监听首可见段落 → VM 内 500ms 防抖 → saveReaderPosition；切章不防抖立即落库
- **配置变更恢复**：LazyListState 经 rememberSaveable(saver) 跨旋屏保持；重进书籍按 initialParagraph scrollToItem 恢复到段级
- **系统效果**：亮度覆盖退出时恢复原值；沉浸模式工具栏显示时临时唤出系统栏；音量键翻章默认关（AC-4.5）

## M5 目录 / 书签 / 收尾功能（已完成）

### T5.1 目录弹层
- 涉及：ui/reader/ReaderSettingsSheet.kt 的 TocSheet（章节列表 + 当前章高亮 + 点击跳转）
- 验收：SPEC AC-6.1 通过 ✅

### T5.2 书签
- 涉及：BookRepository 书签方法（observe/add/delete）、ReaderViewModel 书签状态与动作、目录弹层「书签」Tab（章节 + 摘录 + 时间 + 跳转 + 删除）、阅读器顶栏星标添加入口
- 验收：SPEC AC-6.2 / AC-6.3 通过 ✅（BookmarkRepositoryTest 5 用例：倒序发射 / 位置与摘录 / 单删 / 级联删除 / 多书隔离）

### T5.3 重新解析章节
- 涉及：书架长按菜单「重新解析」（保守/激进两档，M2 已实现并单测覆盖）
- 验收：SPEC AC-6.4 通过 ✅

### 关键实现说明
- **书签锚点**：以当前章首个可见段落为锚（ReaderUiState.currentParagraph 随滚动上报），摘录取该段前 60 字符
- **书签跳转**：goToChapter(chapterIndex, paragraph) 段级恢复，与 ReaderPosition 恢复同路径
- **Tab 面板**：TocSheet 双 Tab（目录/书签），列表修饰符由 ModalBottomSheet 的 ColumnScope 传入（weight 仅在 Scope 内可解析）

## M6 测试 / 构建 / 交付（已完成）

### T6.1 全量单测 + fixture 补全
- 覆盖：94 个单测全绿（导入流水线 / TXT 切分 / EPUB 引擎 / XML 双端 / 仓储 / 书签 / 进度纯函数 / 设置持久化 / 冒烟），fixture 全部程序化构造（含手搓 STORED ZIP 的 GBK 条目名用例）

### T6.2 lint 清零 + release 构建配置
- lint：0 errors / 0 warnings（RedundantLabel / ModifierParameter 已修；mipmap-anydpi-v26 的 ObsoleteSdkInt 为工具链误报——实测移入 mipmap-anydpi 会导致 AAPT2 链接失败，经 lint.xml 按路径豁免并注明原因）
- release：R8 + 资源压缩（10.3 MB → 1.47 MB）；签名走 gradle properties 注入，`scripts/generate-keystore.sh` 生成 keystore，`.gitignore` 屏蔽 *.jks / keystore.properties
- 已验证 release APK 无 INTERNET 权限

### T6.3 装机验证清单 + 最终 APK + GitHub 推送
- [docs/DEVICE_CHECKLIST.md](docs/DEVICE_CHECKLIST.md)：七大类 40+ 项真机验证清单（导入/书架/阅读器/书签/进度/隐私/稳定性）
- 最终 APK：`app/build/outputs/apk/debug/app-debug.apk`（v1.0.0）
- GitHub 仓库创建推送：需用户提供 GitHub Token（gh CLI 当前未认证），见 README「发布」节
