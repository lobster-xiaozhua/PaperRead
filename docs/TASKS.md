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
- book.txt 全文一次性解码进内存的问题已由 B1 流式解码解决（见下方 B1 节）

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

## v1.0.1 审计修复（已完成）

> 依据《PaperRead 项目审计报告》逐项修复，96 单测全绿 + lint 0/0。

### F1（P1）重新解析后书签失配
- `BookRepository.deleteBookmarksByBook` + `reparseBook` 清空书签（章节语义已变，旧 bookmarks 必然错位）
- 回归测试：BookImporterTest「reparseBook … clears bookmarks」+ BookmarkRepositoryTest「deleteBookmarksByBook clears all」

### F2（AC-1.4）导入可取消
- `copyAndHash` 改为 suspend，逐缓冲区 `ensureActive()`；阶段间检查；`CancellationException` 分支清理半成品目录后重抛
- ViewModel 持有 `importJob`，进度对话框「取消导入」/ 外部点击均可取消，取消后提示「已取消导入」

### F3（AC-1.5）判重「替换 / 取消」
- `duplicateTarget` 状态 + `DuplicateDialog`；「替换」= 删除旧书（含文件）后用原 URI/策略重新导入

### F4（AC-5.2）像素级恢复
- 滚动上报改为 `(firstVisibleItemIndex, firstVisibleItemScrollOffset)`；`ReaderPosition.scrollOffset` 真实落库；恢复用 `scrollToItem(index, offset)`

### F5（P4）重新解析失败原因透传
- `reparseBook` 返回 `String?`（null=成功）；源文件丢失返回「源文件已丢失，请删除后重新导入」

### 未处理项（评审后判定）
- 导入期全文驻留内存（流式解码，预估 2 天，v1.1 处理）→ **已由 B1 完成**
- VM/UI 自动化测试与 androidTest（v1.1 引入 Turbine + Compose UI Test）→ B2/B3
- 拆分 `:reader` 独立模块（待代码量增长观察）→ B6 暂缓

## B1 导入流式解码（v1.1，已完成）

> 依据 ROADMAP B1：200 MB GBK TXT 导入成功、峰值内存 < 100 MB、进度平滑。验收：115 单测全绿 + lint 0/0 + assembleDebug 通过。

### 实现
- 新增 `reader/txt/TxtChapterStream.kt`（约 490 行）：`CharsetDecoder` 分块解码 → 增量写 `book.txt` → 边切章边记录双偏移（charStart/charEnd UTF-16 用于进度，byteStart/byteEnd UTF-8 用于读取）
- 流式核心常驻内存与文件大小无关：实测 20 MB 字节输入常驻 13 MB（流式缓冲 256 KB 量级）；章节索引 O(章数) 非 O(文件大小)
- `ChapterSplitter.splitForced` 三段语义在流式侧逐段复刻：目标位 ±200 字符换行吸附、`lastIndexOf` 向后吸附（缓冲内扫 + `lastNewlineFlushed` 兜底已冲刷区域）、末段硬切；边界与批量逐段一致（TxtChapterStreamTest 对齐用例）
- `CharsetDetector.decodesStrictly` 修复前缀截断误判：64 KB 样本末尾的多字节截断不再误报 MALFORMED（详见下方关键实现说明）
- `BookImporter` TXT 分支切换为流式路径；EPUB 分支不变

### 关键实现说明
- **CharsetDecoder 前缀截断语义**：一参 `decode(ByteBuffer)` 等价 endOfInput=true，样本末尾截断报 MALFORMED；三参 `decode(in, out, false)` 截断表现为 UNDERFLOW（待续）。但三参版本**不抛异常**，REPORT 动作的错误以 `CoderResult` 返回，必须显式检查 `isMalformed/isUnmappable`——忽略返回值会让任何输入都被判合法（调试期间实际踩中，表现为 GBK 全被误判为 UTF-8）
- **激进短行候选竞态**：激进策略的候选短行需等下一行完成才能判定 nextBlank，而冲刷上限（最后一个换行 +1）会先掠过候选位置致 bytePos 永久为 -1；AGGRESSIVE 模式下冲刷上限 cap 到 `pendingShortLine.start`（冲刷最多停顿一行）
- **合成行安全**：超过 maxBufferChars 的行在追加期间必触发紧急冲刷 → midLineFlushed → 完成时判 synthetic → 不参与短行/强正则候选，紧急冲刷越过待判行起点仍安全
- **冲刷不变量**：advanceForced 先于每次 flushTo 执行，且只在「缓冲内无 ≥target 换行」的等待态返回，保证向后吸附不漏目标位前的换行
- **测试堆**：5 MB / 20 MB 冒烟测试自身持有约 260 MB 对象（text/bytes/normalized/decoded 等），512 MB 默认堆临界 → `testOptions.unitTests.all.maxHeapSize = "2g"`

### 测试
- TxtChapterStreamTest 18 用例：流式与批量逐段一致（保守/激进/强制前后吸附+硬切）、双偏移 round-trip、取消传播、空文本、合成行
- CharsetDetectorTest +1：UTF-8 前缀截断 1/2 字节仍判 UTF-8
- BookImporterTest 5 MB fixture 章节数 > 5000（修复前因探测 bug 回落 GB18030 仅 2678）
- 全量 115 单测全绿；lint 0/0；assembleDebug 通过（APK 无 INTERNET 权限）

### 待真机验证
- 200 MB GBK TXT 导入 + `dumpsys meminfo` 峰值 < 100 MB + 进度平滑（见 DEVICE_CHECKLIST 第 7 节）

## B2 ViewModel 自动化测试 + 覆盖率度量（v1.1，已完成）

> 依据 ROADMAP B2：`kotlinx-coroutines-test` + Turbine + 手工 fake，覆盖防抖保存、切章落库、书签、导入取消状态机、判重替换。验收：ReaderViewModel / BookshelfViewModel 行覆盖 ≥70%。

### 实现
- 三个状态协作类抽取接口以便测试替身：`BookTextStore` → `FileBookTextStore`、`SettingsStore` → `PreferencesSettingsStore`、`BookImporter` → `DefaultBookImporter`；`AppContainer` 暴露类型仍为接口，ViewModel 构造签名不变（override 方法不得重复声明参数默认值）
- 新增 4 个手工 fake（`app/src/test/.../fakes/`）：`FakeBookRepository`（内存实现 + 调用记录 + `emitBooks/emitBookmarks` 驱动流）、`FakeSettingsStore`（MutableStateFlow 支撑）、`FakeBookTextStore`（可配置 `chapterText` + 调用记录）、`FakeBookImporter`（`prepareGate: CompletableDeferred` 挂起导入 + 全调用记录）
- JaCoCo 接入：`jacocoTestReport` 任务（XML + HTML，排除 R/BuildConfig/Manifest/匿名类），验证入口 `./gradlew :app:jacocoTestReport`

### 关键实现说明
- **JaCoCo 与 Robolectric 的兼容坑（本环境实测，配置已注释在 build.gradle.kts）**：Robolectric 的 sandbox classloader 定义的类 ProtectionDomain 无 source location，agent 默认直接跳过 → Robolectric 测试的类覆盖率恒 0%。开启 `inclNoLocationClasses=true` 后 agent 会插桩 JVM 反序列化时动态生成的 `GeneratedSerializationConstructorAccessor*` 类，导致每个测试 worker 启动即崩（`NoClassDefFoundError`，测试根本不运行）。解法：`inclNoLocationClasses=true` + `includes` 白名单收紧到 `io.github.lunalight.paperread.*`，两者缺一不可
- **500ms 防抖验证**：`advanceTimeBy(300)` → `advanceTimeBy(400)` → `advanceTimeBy(150)` 三段推进虚拟时间，断言 `savedPositions` 只在累计 ≥500ms 时落库且仅最后一次生效；切章显式取消前一个防抖 job（落库次数 +1 且取消防抖）
- **导入状态机挂起点**：`FakeBookImporter.prepareGate`（CompletableDeferred）让 prepare 停在运行态中，配合 Turbine 序列断言「进入运行态 → 阶段标签 → 完成后退出并转待确认」的完整流转
- **Turbine + StateFlow**：StateFlow 会去重发射，`uiState.test { awaitItem() }` 按 distinct 值序列断言；ReaderViewModel 用 `StandardTestDispatcher`（保留防抖时序语义），BookshelfViewModel 用 `UnconfinedTestDispatcher`（即时执行，适合状态机断言）

### 测试
- ReaderViewModelTest 12 用例（纯 JVM）：位置恢复（章/段/像素偏移/进度）、越界夹取、错误态、防抖落库时序、切章落库、书签增删与跳转、设置回流、面板显隐
- BookshelfViewModelTest 19 用例（Robolectric 提供 `android.net.Uri`）：导入运行态/取消/防重入、判重对话框、替换导入（先删旧书再原参数重跑）、确认/取消待确认、重新解析（成功/失败保留）、排序 4 种口径、删除联动、消息消除
- 全量 146 单测全绿；ReaderViewModel 行覆盖 100%（104/104），BookshelfViewModel 100%（63/63），B2 验收达标
