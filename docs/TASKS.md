# PaperRead 开发工单与项目状态

> 每个工单 ≤ 4 小时。退出标准：可运行 APK + `./gradlew testClientDebugUnitTest testDevDebugUnitTest lint assembleDebug` 全绿。
> 协作规则见 [AGENTS.md](AGENTS.md)；验收标准见 [SPEC.md](SPEC.md)；未来规划见 [ROADMAP.md](ROADMAP.md)。
> 本文件只记录**当前状态**：已具备的能力 + 待办/未决项，历史逐条工单已折叠。

## 当前版本

- **最新发布**：v1.2.0（versionCode 4，体验增强版：TTS 语音朗读 US-12 / 局域网传书 US-9 / 换机迁移 US-10 / 进度同步 US-11 / 设置页与关于页 US-8 / 仿真翻页 C7 / 平板双页 US-13）
- **main 未发布变更**：无（干净发布点）
- **质量基线**：534 单测全绿（其中 2 例门控 / skipped）、lint 0 error、行覆盖率以 jacocoTestReport 报告为准（门禁 ≥ 80%，经登记的零覆盖纯渲染类不计入分母，见下「工程与交付」）、CI 七作业配置就位（覆盖率口径调整后待 CI 首次运行验证）

## 当前能力（已完成）

### 工程与交付
- Compose + Material 3 + 类型安全导航脚手架；Gradle 版本目录
- **多模块结构（T-010 / R-12）**：`:app`（Android 应用）+ `:core:domain`（纯 JVM 领域模型与仓储接口）+ `:core:reader`（纯 JVM 阅读引擎，`api` 依赖 `:core:domain`）。两个 `:core:*` 模块只应用 `kotlin("jvm")`，「Reader 引擎零 Android 依赖」由**编译期**强制（写 `import android.*` 即失败），其单测在纯 JVM 上跑（无 Robolectric）。包名保持不变，拆分对调用方透明。仍留 `:app` 的 Android 相关实现：`FileBookTextStore`（`Context.filesDir`）/ `AndroidXmlParser` / `XmlParsers`（Android 工厂），迁移见 T-013
- **覆盖率口径待收敛（T-014）**：阅读引擎代码已不在 `:app` 的覆盖率分母内（`:app` 的 `jacocoTestCoverageVerification` 现在只度量 app 侧代码，门禁阈值不变），跨模块聚合尚未落地——引擎行覆盖率在聚合前不进任何门禁，这是本轮**已知空窗**，由 T-014 的 JaCoCo 聚合补齐
- CI 门禁：七个作业独立并行，强制全绿方可合并 —— `unit`（双 flavor 单测 + JaCoCo 行覆盖率 ≥ 80%，经登记的零覆盖纯渲染类不计入分母）、`robolectric-ui`（Robolectric UI 冒烟 + 失败重试 ≤ 1）、`lint`（Android lint + 文档口径一致性脚本）、`room-migration`（Room 迁移登记 / destructive fallback 禁用静态断言）、`network-audit`（不访问公网 / 无三方网络 SDK 静态断言）、`assemble`（双 flavor 装配 + release R8 打包）、`r8-smoke`（R8 mapping keep 断言 + release 变体序列化 round-trip）
- 覆盖率口径（2026-10 批次 4 整改·最小化 + 显式白名单）：分母默认包含**所有**类；仅**经登记的零覆盖纯渲染类**不计入分母（当前 7 个：`ReaderScreenKt` / `ReaderContentKt` / `ReaderPagerKt` / `ReaderChromeKt` / `SpeechControlBarKt` / `TransferScreenKt` / `ReaderSettingsSheetKt`，显式类名清单见 `app/build.gradle.kts` 的 `coverageExcludedRenderClasses`）。登记条件两条缺一不可：① CI 口径（`-PexcludeUiSmokeTests=true`）下该类 0%（完全未执行）；② 无任何 CI 测试代码 import / 调用其逻辑成员（仅被 `ui.smoke` 渲染用例引用不算被测）。**T-011（R-11）后阅读器从 1 个 645 行 God File 拆成 6 个文件**：编排层 `ReaderScreen.kt` 归零为「状态收集 + 事件分发 + 部件组合」，正文 / 翻页 / 外壳 / 朗读条各自成类并逐个登记白名单（与拆分前同一批渲染行、同一条登记理由，只是按类重新归属），而**系统效果（`ReaderEffectsKt`）合并到分母内度量**——因此拆分没有靠扩大白名单来「凑」覆盖率。**不再按 `*ScreenKt` / `*DialogKt` 等模式通配剔除**——通配会让新增的 `FooScreen.kt` 自动脱离度量，并让被测试直接调用的逻辑函数（如 `ImportResultDialogKt.formatFileSize`、`LicenseScreenKt.readLicenseText`）连同文件一起从分母消失；白名单后新增文件默认进分母，逻辑进 `*Screen.kt` 也会被度量，要划出分母须显式登记并说明理由。UI 行为由 `robolectric-ui` 作业的行为冒烟承载，两个作业口径互补
- CI 结构演进（2026-10）：门禁原为「合并成单次 Gradle 调用」以省配置阶段，现改为七作业独立 —— 理由是**失败定位清晰**（哪道门禁红一目了然，不必翻单次构建日志）+ **flaky 隔离**（Robolectric 渲染在内存受限 runner 上抖动，重试 ≤ 1 隔离在 `robolectric-ui`，不阻塞 `unit`）；代价是各作业各付一次配置阶段（AGP + KSP + Room + Compose，约 10s），多作业并行执行下总时长不劣于单作业。其余提效手段保留：Robolectric android-all jar（API 34 运行 SDK + API 35 compileSdk，约 335MB/轮）经 actions/cache 跨运行复用；单测双 fork 并行；纯文档变更（md / docs）跳过 CI；产物保留期收敛为 14 天
- 开源物料：README / LICENSE(Apache-2.0) / CONTRIBUTING / AGENTS / SECURITY / Issue 模板 / PR 模板 / CODEOWNERS / .editorconfig

### 导入
- TXT 导入：SAF 选文件 + 流式拷贝 + SHA-256 去重 + 磁盘预检（空间 1.5 倍）
- 编码探测：BOM(UTF-8/UTF-16) → 严格 UTF-8 → GB18030 回退，结果页可手动切换
- EPUB 导入：OPF / spine / NCX 解析、DRM(encryption.xml) 检测拒绝、GBK 条目名兜底
- 大文件流式导入：200 MB 级 TXT 常驻内存与文件大小无关（分块解码 + 双偏移懒加载）
- 导入失败原因结构化：`ImportError` 密封类 8 子类（stage / cause / userMessage），UI 直接取话术

### 解析与数据
- 智能章节切分：强正则 + 目录页跳过 + 启发式（空行短行 / 序号 / 重复书名）+ 强制分段回退
- 双偏移体系：字符偏移（进度）+ 字节偏移（读取），按章懒加载
- 数据层：Room（书库·章节·书签·进度，双偏移索引）+ DataStore 设置 + Repository

### 书架与阅读器
- 书架：继续阅读 / 网格 / 程序化封面 / 四种排序 / 多选删除 / 重新解析（保守·激进两档）
- 阅读器：LazyColumn 段落排版（百 MB 不卡顿）/ 5 套主题 / 亮度 / 常亮 / 沉浸 / 音量键翻章（默认关）
- 进度恢复：段级 + 像素级（500ms 防抖 + 切章立即落库 + 退后台即时落库），字符加权百分比无跳变
- 目录 / 书签 / 阅读设置（字号·行距·段距·边距，实时生效自动持久化）

### 设置与关于
- 设置页：主题 / 字体 / 字号 / 行距 / 段距 / 页边距 / 亮度 / 屏幕常亮 / 沉浸模式 / 音量键翻章，与阅读器底部弹层共用同一套控件（`ReadingPreferencesPanel`）与数据源（`SettingsStore`），实时生效、自动持久化
- 关于页：应用名与版本号、隐私承诺（最小权限：唯一 INTERNET，仅用于局域网传输，不连接互联网）、Apache-2.0 许可全文、GitHub 主页与 Issue 反馈（系统浏览器打开，无内置 WebView）、无第三方依赖声明、维护者信息

### 局域网传书（T2）
- UDP 广播发现（协议魔数 / 版本 / 设备名 / HTTP 端口，广播才应答防乒乓，3 秒内可被发现）
- 零依赖 HTTP 传输（JDK 原生 socket）：`GET /meta` + `POST /upload`，服务仅绑 WiFi 网卡地址（不听其他接口）
- 书架多选发送（进度 + 可取消，失败携带原因并清理现场）；接收端落盘 cacheDir → FileProvider → 复用既有导入流水线（SHA-256 去重 / 解析 / 待确认，单一逻辑）
- 设备标识为随机持久化 UUID（不使用 MAC / IMEI / ANDROID_ID）

### 换机迁移（T3）
- 整库打包为单一 zip：清单（每本书元数据 + 进度 + 书签）+ 源文件（按 SHA-256 去重）
- 接收端逐本导入（复用导入流水线），单本失败记入清单、不中断整体，结束汇报成功 / 失败明细
- 进度与书签按「最后阅读时间较新者胜」回写，已存在的书不重复导入
- 迁移包解包含路径穿越防护（可疑条目丢弃、不中断）

### 备份与测试
- 备份策略（方案甲-B）：云备份排除书库目录（`books/`），换机（D2D）保留整库；双 XML 规则覆盖 Android 11–12+
- 测试：JVM 单测 + Turbine ViewModel 测试 + Robolectric Compose UI 冒烟 + JaCoCo 覆盖率度量

## 未发布技术债（v2.0 前跟踪）

> 均为「已知、可接受、v2.0 前处理」的存量项，不阻塞当前发布；每条附依据，便于复核。

- speech 包行覆盖率 82.2%（TtsSpeechEngine 25% / AndroidSpeechController 31.4%）——距 80% 阈值余量薄，后续给该包加分支容易直接破门禁；依据：`jacocoTestReport` 实际 XML 逐类统计（CI 口径 `-PexcludeUiSmokeTests=true`）
- 覆盖率白名单 7 类内的存量逻辑 —— **T-011（R-11 God File 拆分）已消解其中两项**：
  - ✅ 已消解：`ReaderScreen.kt` 的**亮度保存 / 还原**与 `SpeechService` 编排（前台服务 start/stop）已外移到 `ui/reader/ReaderEffects.kt`；该文件**刻意不登记**白名单，如实计入覆盖率分母（CI 口径 0/50 行，属该补测的窗口/服务状态机逻辑），这两处从此受门禁度量。依据：`coverageExcludedRenderClasses` 不含 `ReaderEffectsKt` + `jacocoTestReport` 逐类行数
  - ⬜ 仍未消解：朗读通知权限 launcher（`rememberLauncherForActivityResult` + `SpeechPermissionEvent` 消费）——与 Compose 的 Activity result 契约绑定，只能留在编排层（`ReaderScreenKt` 仍在白名单内），需先补 `unit` 口径用例（当前仅 `ReaderSpeechPermissionTest` 这条 `robolectric-ui` 用例覆盖）才能移出；`formatBookmarkTime` 随 `TocSheet` 从 `ReaderSettingsSheet.kt` 迁到 `ui/reader/ReaderChrome.kt`，仍在白名单内（本文件内私有，仍无 `unit` 用例）
  - 依据（原始口径）：`app/build.gradle.kts` 的 `coverageExcludedRenderClasses` 维护约定「已登记类新增逻辑应移出白名单」
- `CoverGenerator` 0/14、`RememberChapterPagesKt` 0/26——如实留在分母内计账（不靠剔除掩盖），属该补的测试；依据：同处「刻意**不**剔除」清单与 `jacocoTestReport` 逐类 missed / covered 行数。**T-011 决定**：`rememberChapterPages` **不**并入 `ReaderPager.kt`——并入后它会落进白名单类里，那 26 行被度量的事实会随白名单一起消失（正是配置注释里点名要防的「后门」），故保留独立文件
- 跨页索引算术：`pageIndexForPosition` 已是 `Paginator.kt` 里的纯函数且有单测（`PaginatorTest` 的「反查正确」用例），该项已消解；残留的渲染侧调用（`(pageIndexForPosition(...) / 2) * 2` 的 spread 配对）随 T-011 并入 `ReaderPagerKt`（白名单内），仅由 `ReaderSpreadSmokeTest` 验证
- 建议加机械护栏：断言 7 个白名单文件内不存在非 `@Composable` 的顶层 `fun`（脚本级静态断言，防「逻辑悄悄进渲染文件」复发）；依据：白名单按类名生效，靠人工 review 拦不住回归
- 沙箱不可执行项：200MB 大文件六项真机验收（G5）、R8 后真机 install smoke（G7，需 KVM runner）、CI 七作业尚无真实运行记录；依据：`docs/DEVICE_CHECKLIST.md` §7 与 `.github/workflows/android.yml` 中预留的注释步骤

## 规划工单（T 系列，局域网传输）

> 来源：用户 2026-10-18 拍板（ROADMAP C9 决策点 2）——项目允许申请权限、保持最小权限：INTERNET 进主包（Android 无「仅局域网」权限，任何 socket 均需声明），传输能力进主包；分发拆 client（客户端正式版）/ dev（调试版，可同机安装）两个 flavor。验收标准见 SPEC 第 7、9 节（US-9 / US-10 / US-11）。
> 权限口径后续修订（2026-10-19，ROADMAP C2 拍板）：TTS 朗读为后台常驻播放，追加前台服务三件套（`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `POST_NOTIFICATIONS`），详见 T6 与 SPEC 第 7、10 节。
> 门禁：`./gradlew testClientDebugUnitTest testDevDebugUnitTest lint assembleDebug` 全绿；应用除 INTERNET 与朗读前台服务三件套外不得持有任何权限。

### T1 flavor 拆分与最小权限（约 1.5h）

- 涉及：`app/build.gradle.kts`（flavorDimensions + client/dev，后者 applicationIdSuffix `.dev` / versionNameSuffix `-dev` / IS_DEBUG_BUILD 标记）、主 `AndroidManifest.xml`（唯一权限 INTERNET）、jacoco 任务改绑 clientDebug、CI 任务名与 APK 上传路径 flavor 化、关于页权限文案与 `AboutScreenTest` 权限断言改为最小权限口径
- 约束：除 INTERNET 外零权限；client 为唯一对外交付版；dev 与 client 可同机安装
- 验收：SPEC AC-7.1 – AC-7.4；CI 两 flavor 测试 + 覆盖率门禁 + lint + 双 APK 产物全绿

### T2 传书核心（约 4h）✅ 已完成（feat/transfer-core，244 单测全绿）

- 涉及：`app/src/main/java/.../transfer/`——UDP 发现（广播/响应编解码，纯 JVM 可测）、极简 HTTP 服务（JDK ServerSocket，仅绑定 WiFi 网卡地址）、书架多选发送入口、接收端落盘 + FileProvider 复用现有导入流水线
- 交付物：
  - `transfer/`：`TransferProtocol`（UDP 报文 + 编解码，魔数 / 版本校验）、`LanDiscovery`（发现状态机，广播才应答防乒乓，注入时钟可测）、`TransferHttpServer` / `TransferClient`（零依赖 HTTP，仅绑 WiFi 网卡地址）、`LanAddresses`（wlan* IPv4 站点本地）、`DeviceIdentityStore`（随机 UUID 落盘 + Build.MODEL 设备名）、`LanTransferManager`（编排：服务生命周期 / 发送 / 接收 / 待确认，接口隔离便于 fake）
  - `ui/transfer/`：`TransferViewModel`（四状态流透传 + 书目按 id 实时解析 + 一次性消息）、`TransferScreen`（本机标识卡 / 待发送书目 / 在线设备 / 发送进度可取消 / 接收进度 / 复用共享 `ImportResultDialog`）
  - 入口：书架多选顶栏发送按钮 + overflow「局域网传书」；`TransferRoute(bookIds)` 类型安全导航；`AppContainer.transferManager` 装配
  - `ImportResultDialog` 抽取为 shared internal（本地导入与传书接收同一对话框，AC-9.5 单一下导入逻辑）
  - 测试：传输核心 28 用例（含 localhost 真实 socket 端到端）+ `TransferViewModelTest` 14 用例（FakeLanTransferManager）+ 传书页 UI 冒烟；新增 4 个矢量图标
- 约束达成：零新增第三方依赖（JSON 用已引入的 kotlinx.serialization）；传输核心纯 JVM 单测覆盖（localhost 真实 socket）；UI 层不直连 socket
- 验收：SPEC AC-9.1 – AC-9.7 全部达成（AC-9.7 由「仅绑 wlan* 地址」实现 + lint / 代码审查保证）

### T3 换机迁移（约 3h）✅ 已完成（feat/migration-core，271 单测全绿）

- 涉及：整库迁移包（书籍 source + 进度/书签 JSON，zip）打包 / 解包 / 逐本导入 / 按 SHA-256 回写进度书签
- 交付物：
  - `transfer/MigrationBundle.kt`：`MigrationManifest` / `BookStateSnapshot` / `BookmarkSnapshot` 数据契约；`MigrationBundleCodec.pack/unpack`（zip + JSON；源文件按 SHA-256 命名去重；解包拒绝绝对路径与 `..` 穿越，可疑条目丢弃而不中断；高版本清单 / 损坏包返回 null）；`WriteBackDecider` 回写决策纯函数；`Book.toSnapshot` 域模型转换
  - `transfer/MigrationProcessor.kt`：接收编排（`MigrationImportGateway` 接口 + `MigrationProcessor`）——逐本处理、单本失败入清单不中断、已存在（SHA-256）不重复导入、导入后按「最后阅读时间较新者胜」回写进度与书签（两者同一决策单元），产出 `MigrationReport`
  - `TransferHttpServer`：新增 `POST /bundle` 端点（`X-Bundle-Name` 头），与 `/upload` 共用前导逻辑；`TransferClient.uploadBundle`
  - `LanTransferManager`：迁移状态流 `migration`（方向 / 阶段 / 进度 / 计数 / 清单 / 错误）；发送端「收集书库 → 打包 → 上传」；接收端「落盘 → 后台解包 → 逐本导入 → 回写」（HTTP 尽早应答避免对端超时）；Android 侧网关实现复用 `BookImporter` 与 `BookRepository`，无第二套导入逻辑
  - UI：传书页新增「换机迁移」卡片（阶段 / 进度 / 可取消 / 结果清单）与设备行「迁移整库」按钮；VM 汇总一次性提示
  - 测试：`MigrationBundleTest` 9 项（往返 / 缺源 / 坏包 / 高版本 / zip slip / 未知条目 / 回写决策矩阵 / 快照）、`MigrationProcessorTest` 7 项（逐本导入 / 单本失败不中断 / 已存在不重复导入 / 本地较新不回写 / 缺源记失败 / 进度回调 / 空清单）、`/bundle` 端到端 1 项、VM 迁移 4 项、`ImportResultDialogTest` 5 项（共享对话框）
- 约束达成：单本失败不中断整体（AC-10.3）；回写按「最后阅读时间较新者胜」（AC-10.4）
- 验收：SPEC AC-10.1 – AC-10.4 全部达成
- 已知边界：回写走 `saveReaderPosition`，会将 `lastReadAt` 置为当前时间（语义上「本地态即最新」，符合 AC-10.4 单向迁移场景）

### T4 进度同步（约 3h）✅ 已完成（feat/progress-sync，295 单测全绿）

- 涉及：双向阅读状态交换、设备标识（随机 UUID 持久化，禁硬件标识）、last-write-wins 冲突解决、单侧数据合并
- 交付物：
  - `transfer/ProgressSync.kt`：`SyncBookmark` / `BookSyncState` / `SyncEnvelope` 数据契约；`SyncMerger.mergeBook/merge` 纯函数合并决策——进度按「最后阅读时间较新者胜」且覆盖与回推**互斥**（避免两台设备互相踩），书签按 `章节:段落:字符偏移` 去重后**只增不删**，单侧独有书只计数不导入
  - `transfer/ProgressSyncCoordinator.kt`：`LocalSyncGateway` / `RemoteSyncGateway` 双端口抽象 + 协调器「读本地快照 → 拉对端状态 → 合并 → 应用本地 → 回推差异」一次收敛
  - `TransferHttpServer`：新增 `GET /sync/state`（返回 JSON 快照）与 `POST /sync/apply`（接收差异并落库），请求体上限 8 MB；`TransferClient.fetchSyncState` / `pushSyncState`
  - `LanTransferManager`：同步状态流 `sync`（进行中 / 阶段文案 / 错误 / 报告）、`startSync` / `dismissSyncReport`；接收端 `onSyncApply` 对书签去重后只补缺
  - UI：传书页新增「阅读进度同步」卡片（进行中 / 错误 / 结果计数）与设备行「同步进度」按钮；VM 汇总一次性提示（覆盖 N 本 / 新增 N 条 / 回推 N 本 / 对端另有 N 本）
  - 测试：`SyncMergerTest` 18 项（进度 5 / 书签 4 / 整库 4 / 协调器 5）、`/sync/state` 与 `/sync/apply` 端到端 3 项、VM 同步 5 项
- 约束达成：合并决策纯 JVM 覆盖；书签合并不删除对侧独有数据（接口无删除能力，从设计上排除）
- 验收：SPEC AC-11.1 – AC-11.4 全部达成
- 实现备注：`readBody` 未用 `InputStream.readNBytes`（API 33+），改为手写循环读取以守住 minSdk 26

### T5 仿真翻页（约 3h）✅ 已完成（feat/page-turn，305 单测 / lint / assembleDebug 全绿）
- 涉及：阅读器新增「翻页模式」开关（`ReaderSettings.pageTurnMode`、`SettingsStore` 持久化、`ReadingPreferencesPanel` 行为区开关，与设置页 / 阅读器弹层共用）；`reader/Pagination.kt` 纯分页算法（段边界切页，零 Android 依赖）；`ui/reader/ReaderScreen.kt` 新增 `ReaderPagerContent` / `ReaderPagerScaffold` / `PageContent` / `PageEdgeShadows`（官方 `HorizontalPager` + `TextMeasurer` 段落测高 + 边缘阴影 + 观察者式点按三区 + 音量键翻页 + 越章衔接）；`ReaderViewModel.onPagePosition` 按页起始段折算字符偏移落库（复用 `ReaderPosition`，不新增进度字段）
- 交付物：
  - 翻页模式与连续滚动互斥共存（默认滚动），开启后正文左右滑动翻页、书页边缘阴影随拖动增强；点按左 / 中 / 右区翻页 / 唤出工具栏；音量键翻页（需开启音量键开关）；页码指示；恢复 / 切章定位到含初始段的页
  - 分页核心 `paginate` / `pageIndexForStartParagraph` 纯 JVM 单测 8 项；`ReaderViewModelTest` 翻页进度 2 项；新增 `compose-foundation`（官方 AndroidX，已在 Material3 传递依赖，显式声明）
- 约束达成：零新增第三方依赖（卷曲效果未做，按此前确认的「先滑动、卷曲后续增强」）；进度数据模型不变；`reader/` 引擎零 Android 依赖（分页算法纯函数）
- 验收：SPEC 无专门 AC（C7 为体验增强候选）；回归：滚动模式路径未改动
- 已知边界：分页按「段边界」对齐，不做段内字符级拆分；单段高于整屏的极端段落独占一页并允许纵向滚动，不裁切丢字；超大单章书（无章节切分）翻页模式需对全章段落测高，建议仍用滚动模式

### T6 TTS 语音朗读（约 6h）✅ 已完成（feat/tts-core，单 flavor 331 单测全绿）

- 涉及：系统 TextToSpeech 离线朗读 + 前台服务保活 + 阅读器朗读编排 + 朗读设置持久化
- 交付物：
  - `speech/`：`SpeechText`（纯 Kotlin：渲染段落 → 朗读单元表，空白段跳过、超长段按句末标点软切分至 ≤3500 字符，utteranceId `p{段}-{part}` 与段下标双向映射）、`SpeechController` 接口（SPEAKING/PAUSED/IDLE 状态流 + speak/pause/resume/stop/setRate/setPitch/onFinished）、`AndroidSpeechController`（`android.speech.tts` 实现：构造切主线程、单一锁保护播放位置、逐单元推进、pause = stop + 记住位置、调速重读当前单元、引擎失败/缺中文语音显式置不可用原因）、`SpeechService`（前台服务 mediaPlayback：只保活 + 常驻通知，不持播放逻辑）、`SpeechActionReceiver`（通知四按钮广播 → 控制器）、`SpeechNotifications`（通知渠道 + 媒体风格通知，contentIntent 直达当前书阅读器）
  - `ui/reader/`：`ReaderViewModel` 朗读编排（从当前可见段起播、暂停切换、段步进、章尾续读走 `goToChapter(restartSpeech=true)` 避免双 speak、切章不断播、离开阅读器即停）、`ReaderScreen` 顶栏朗读入口 + 底部朗读控制条（播放暂停 / 段步进 / 停止 / 语速音调滑杆 / 自动续读开关）+ 朗读段高亮与滚动跟随 + TTS 不可用 Snackbar
  - `data/settings/`：`ReaderSettings` 新增 `ttsRate = 1.0f` / `ttsPitch = 1.0f` / `ttsAutoNextChapter = true`，`SettingsStore` 三 key 读写（缺 key 回退默认，升级兼容）
  - `di/AppContainer`：`speechController` 进程单例（internal setter 为测试注入接缝）；`MainActivity` 通知权限申请（API 33+）；`AndroidManifest` 前台服务三件套权限 + Service/Receiver 声明
  - 测试：`SpeechTextTest` 10 项（纯 JVM）、`ReaderViewModelTest` +11 项（起播定位 / 暂停切换 / 空白段跳过 / 章尾续读 / 关闭续读 / 切章不断播 / 段步进 / 语速音调持久化 / 不可用透出 / 长章单元口径）、`SpeechServiceTest` 2 项（Robolectric 前台通知 + 广播动作翻译）、`AboutScreenTest` 权限白名单更新、`UiSmokeTest` 朗读入口冒烟
- 约束达成：零新增第三方依赖（仅 `android.speech.tts`）；朗读不发起任何网络请求（AC-7.5）；reader/ 分层纪律保持（SpeechText 零 Android 依赖）
- 人工把关项备案（已向用户说明并获批准）：① 权限变更——前台服务三件套，理由：用户拍板「后台常驻播放」，系统要求前台服务必须声明，POST_NOTIFICATIONS 为用户拒绝时仍可播放仅通知不可见；② 并发——合成线程 UtteranceProgressListener 回调与主线程变更共用单一锁，对外状态只经 StateFlow 原子发布，onFinished 锁外回调；③ 数据模型——朗读设置三字段新增（默认值即开箱可听，缺 key 回退保证升级兼容）
- 验收：SPEC AC-12.1 – AC-12.8 全部达成；门禁三件套全绿（lint 0 error、新增代码 0 warning）
- 已知边界：暂停后恢复从当前段开头重读（系统 TTS 无 pause API，AC-12.2 明示）；系统无中文语音数据时置不可用原因并提示安装；通知按钮在暂停态以「暂停位」为锚点步进（发布态保留暂停位置）；翻页模式下朗读高亮与自动翻页跟随已一并适配（变基合入 T5 翻页模式时补齐）

## 待办 / 未决项

### 真机验收（非阻塞，代码已合入）
- B1：200 MB GBK TXT 导入峰值内存 < 100 MB、进度平滑（`dumpsys meminfo` 验证）
- B5：设备侧备份验收（Android 11 `adb backup` 不含 `books/`；Android 12+ `bmgr backupnow`）

### v1.2 体验增强（范围待定）
> 见 ROADMAP C 段，下列为候选，隐私红线（零网络权限）不变更

- C1 全书搜索（关键词 + 定位到段）· C3 自定义字体（导入 ttf/otf）
- C4 书签备注 + 按章分组 · C5 本地备份/恢复（zip 导出导入）· C6 平板双栏/横屏优化
- C7 仿真翻页动效 · C8 国际化（英文）· C9 Wi-Fi 传书（**已拍板：最小权限 + client/dev 双 flavor，见 T 系列工单**）
- ~~C2 TTS 朗读~~（**已交付：系统 TTS 离线朗读 + 后台常驻播放，见 T6**）

### v2.0 方向（待需求验证）
- Markdown / HTML 单文件导入 · 阅读统计（全本地）· 无障碍（TalkBack / 动态字号）· 桌面端可行性

## C6 平板适配（已拆工单，分支 feat/c6-tablet）

> 来源 ROADMAP C6「平板双栏/横屏优化」。决策：真·分页双页（Expanded 自动双页、Compact/Medium 单栏回退）+ 依赖 androidx.window:window。进度模型 ReaderPosition 不变。
> 门禁：`./gradlew testDebugUnitTest lint assembleDebug` 全绿；CI 不引入 INTERNET 权限。

### C6-1 适配基础设施与分页纯函数（约 3h）
- 新增依赖 `androidx.window:window`（libs.versions.toml + app/build.gradle.kts）；README 依赖清单登记
- 新增 `ui/adaptive/WindowSizeClass.kt`：`rememberWindowWidthClass` + `LocalWindowWidthClass`（WindowMetricsCalculator + WindowSizeClass.compute）
- 新增 `ui/reader/Paginator.kt` 纯函数（`PageBreak` / `packPages` / `pageIndexForPosition`）+ `ui/reader/PaginatorTest.kt`（JVM 单测）
- `ui/nav/PaperReadNavHost.kt` 根挂 `CompositionLocalProvider(LocalWindowWidthClass)`

### C6-2 阅读器双页跨页（约 4h）
- 新增 `ui/reader/rememberChapterPages.kt`（Paragraph 度量段高 → packPages）+ `ui/reader/PaginatedSpread.kt`（左右双页 + 章节标题 + 三区点按翻页）
- `ui/reader/ReaderScreen.kt` 双模式分支（`twoPage = twoPageSpread && widthClass==EXPANDED`），复用 ReaderPosition 进度上报/恢复；Compact/Medium 路径零改动；`ChapterFooter` 提为 internal 复用
- 新增 Expanded 下双页 Robolectric 冒烟（可选）

### C6-3 响应式书架/弹层/对话框（约 2.5h）
- `ui/bookshelf/BookshelfScreen.kt`：`GridCells.Fixed(2)` → `GridCells.Adaptive(minSize=160.dp)`
- 导入确认/重解析/判重 `AlertDialog` 增加 `widthIn(max=560.dp)` 居中
- 阅读单栏宽屏 `widthIn(max=840.dp)` 居中 + 横屏 padding（已部分具备）

### C6-4 双页开关与文档收尾（约 2h）
- `data/settings/ReaderSettings.kt` 新增 `twoPageSpread: Boolean = true`
- `data/settings/SettingsStore.kt` 新增 `KEY_TWO_PAGE` 映射与写入；`SettingsStoreTest` 同步断言
- `ui/reader/ReaderSettingsSheet.kt`「行为」区加「双页跨页」开关
- 更新 README 依赖清单、docs/SPEC.md（US-9/AC-9.x）、CHANGELOG.md
- 最终门禁 `testDebugUnitTest + lint + assembleDebug + assembleRelease` 全绿

### 架构暂缓
- B6 拆分 `:reader` 为独立 Gradle 模块（待 reader 包代码量增长再评估）

### 未发布技术债
- AGP 升级消解 R8 对 Kotlin 新版元数据的解析警告（当前非致命，保守保留类）
- 已知限制：TXT 作者恒为「未知」；目录页与正文首章无空行分隔的极端边界；亮度手动调节仅阅读器内生效

### 待用户拍板
- v1.2 范围：C1/C3/C5 是否照单全收（C2 TTS 已交付，见 T6）
