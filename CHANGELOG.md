# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与[语义化版本](https://semver.org/lang/zh-CN/)。
每个正式版本的完整说明见 [GitHub Releases](https://github.com/lobster-xiaozhua/PaperRead/releases)。

## [0.2.0] - 2026-10-28

> 本条目按 [docs/release-notes-template.md](docs/release-notes-template.md) 五段组织（AC-21.3）。

### 新增

- **本地备份 / 恢复（US-14 / T-015 / T-016 / R-13）**：设置页新增「备份与恢复」区块
  - 导出：单一 zip = `manifest.json`（明文摘要）+ 每本书的阅读状态 + 源文件；进度回调 + 可取消；失败 / 取消不留半成品
  - 可选口令加密：PBKDF2-HMAC-SHA256（100,000 迭代 / 256 bit / 16B 随机 salt）派生密钥，条目级 AES-256-GCM（每条目独立 12B nonce）；`crypto.json` 只存 KDF 参数、salt 与 verifier，口令不落盘、不进日志
  - 恢复：版本门控（高版本明确拒绝）、manifest 内容摘要 / 条目大小 / SHA-256 三重完整性校验、逐本走共享导入流水线、SHA-256 命中跳过、单本失败不中断并给失败明细、`WriteBackDecider` 保证旧进度不覆盖新进度、口令错误原地重试
  - 备份不含 `book.txt`（派生文件，恢复时重建）与字体文件（只记引用；缺失回退默认字体并提示）
  - 共享导入网关（AC-9.5 / AC-14.4）：`core:data` 新增 `BookImportGateway` / `AndroidBookImportGateway` / `BookStateWriter`，迁移与备份共用同一条 `prepare → confirm` 流水线与同一套回写动作；迁移侧改为委托，行为逐行不变
  - 测试：新增 49 例（含真实 Room + 导入流水线 + FileProvider 的「导出 → 删库 → 恢复 → 全量比对」端到端用例）
- **传输配对鉴权（R-01 / G1）**：同一 WiFi 下的传书 / 迁移 / 进度同步增加配对门——未配对拒绝一切请求
  - 配对码：展示端 SecureRandom 生成 6 位数字（当面展示 / 输入，不经网络传输）
  - secret 派生：PBKDF2-HMAC-SHA256（100,000 迭代 / 256 bit，salt = 双方设备 ID 排序拼接的 SHA-256）
  - 请求令牌：HMAC-SHA256 覆盖 `method + path + ts + nonce + sha256(body)`，±60s 时间窗 + nonce LRU 缓存（1024 / TTL 5 分钟）防重放
  - 配对 UI：展示端生成配对码并展示等待、发送端输入并探测配对、配对状态与错误提示（取消配对 / 配对失效可重来）
- **全书搜索 · 当前书（US-15 / T-017 / R-14）**：阅读器顶栏新增「本书搜索」——关键词流式扫描当前书，先命中的章节先展示，结果定位到段并高亮（滚动 / 翻页 / 双页三条渲染路径同一份高亮口径）；可随时取消、取消后回到发起前的阅读位置且不改动已保存进度；不建索引、不整本载入内存
- **书签备注与按章分组（US-17 / T-019 / R-18）**：书签可写 ≤ 500 字备注（添加后自动进入编辑、常驻 n/500 计数），列表按章分组展示；备注随同步与迁移跨设备互通（新旧版本互相兼容：新版收旧数据按默认值接纳、旧版收新数据忽略未知字段），合并仍按「章节 + 段落 + 字符偏移」去重、只增不删
- **自定义字体（US-16 / T-018 / R-19）**：阅读设置新增「导入字体文件」——经系统文件选择器选取本机 TTF / OTF（魔数校验 + 20MB 上限），显示名取自字体 `name` 表（零依赖字节解析，零新增依赖）；选用后正文立即生效并持久化，文件缺失 / 损坏静默回退默认字体
- **新手引导（US-18 / T-020 / R-20）**：首次启动三屏引导（数据只存本机不联网 / 如何导入与局域网传书迁移 / 听书依赖系统 TTS 中文语音包），可滑动 + 跳过；升级用户（有使用痕迹）不弹；设置页可重新查看；空书架提供空态（主行动「导入书籍」+ 次行动「局域网传书」）
- **反馈与诊断导出（US-19 / T-021）**：关于页新增「导出诊断信息」——崩溃日志环形保留 10 份（链式钩子记录后照常转发系统处理器）、导入失败环形保留 5 条；先展示摘要再经系统文件选择器导出 zip（`summary.txt` / `crashes/*.log` / `import-errors.json`），私有路径 / 书籍目录名 / UUID 脱敏，不含书籍正文与个人信息，取消或失败不留半成品
- **传输完整性：先验签、后落盘**：POST 请求携带 `X-Content-Sha256` 摘要声明，服务端以声明摘要验签（令牌时间窗只覆盖请求发起时刻，大文件传输不中途失效），落盘后核对实际摘要与声明值，不符即拒绝并清理——落库流水线只见双重校验通过的字节
- **接收进度与中止**： spill 落盘阶段实时回报进度（字节比），断链 / 数据不符触发中止回调并复位 UI
- **`SECURITY.md`**：攻击面说明、威胁模型（资产 / 攻击者 / 向量缓解 / 已知限制）、传输加密（TLS）后置决策记录、漏洞报告流程

### 变更

- **协议版本 v1 → v2**：`PresencePacket`（UDP 发现报文）增加 `pairingRequired` 字段；v1 报文一律拒绝（版本门控优先于字段兼容），**v1 与 v2 设备无法互相发现**
- **配对为会话级**：secret 仅存内存，停止传输服务或取消配对即清零；重新开放传书需重新配对
- **401 语义映射**：对端未配对 / 配对失效时，发送侧提示「对端未配对或配对已失效，请重新配对」而非笼统网络错误
- **UI 文案资源化（US-21 / T-022，AC-21.4 / AC-21.5）**：UI 层 302 处中文字面量清零收口进 `strings.xml`，纯搬运、内容零变更（唯一改写是拼接句改占位符）；新增 `UiText` 文案载体让 ViewModel 不持有 Context；数据层话术（参与序列化 / 跨设备传输）按登记制逐条留档
- **无障碍基线（US-22 / T-023）**：书籍卡合并朗读「书名，进度」、设置开关行级描述、书签行按钮标签、底栏翻页按钮作为三区点按 / 滑动翻页的 TalkBack 替代入口、可点控件触达高度 ≥ 48dp；语义树冒烟并入 CI，TalkBack 实际走查与 200% 字号按 `docs/DEVICE_CHECKLIST.md` §9 真机采集
- **传输 / 导入域 God File 拆分（T-012 / R-11 第二批）**：三个千行级文件按职责拆分，**行为零变更**（并发语义 / 错误话术 / 状态机转移逐行搬迁）
  - `transfer/LanTransferManager.kt`（1131 行）→ 200 行：`LanTransferManager` 接口 + `DefaultLanTransferManager` 门面（构造签名逐字未变，DI / UI / fake 零改动）
    - `TransferCoordinator.kt`：五条状态流 + 配对状态的唯一持有者、服务生命周期（WiFi 地址 → HTTP 服务 → UDP 发现）、配对门、四个流程的装配
    - `TransferSendController.kt` / `TransferReceiveController.kt` / `TransferMigrationController.kt` / `TransferSyncController.kt`：传书发送 / 接收（单槽语义 + 后台解析）/ 换机迁移 / 进度同步各自成类，各自持有作业与状态写入
    - `TransferStates.kt`（状态数据模型）/ `TransferFileNames.kt`（`sanitizeFileName` 纯函数）：同包移动，类名不变
  - `transfer/TransferHttpServer.kt`（725 行）→ 三个文件：帧层 `TransferHttpServer`（accept / 派发 / `TransferHttpConnection` 请求解析与响应写回，~200 行）+ 语义层 `TransferServer`（鉴权中间件 → 五端点路由 → spill 落盘 + 摘要核对）+ 客户端 `TransferClient`（含 `UploadResult` / `PairingProbeResult`）
  - `data/repository/BookImporter.kt`（716 行）→ 125 行门面 + `PreparePipeline`（预检 / 拷贝 / 去重 / 解析编排 / 错误翻译）+ `ImportSource`（唯一接触 SAF 的一层）+ `ImportModels.kt`（数据契约同包移动）
  - **BookParser 注册表（`:core:reader` 契约）**：`reader/BookParser.kt` 定义 `BookParser` / `ParseContext` / `ParseResult` / `ParsePhase` / `BookDecodeException`（零 Android 依赖，不反向引用 `:app`）；`BookParserRegistry` 按 `BookFormat` 分发，`TxtBookParser` / `EpubBookParser` 各自实现——**新增格式 = 实现接口 + 在 `defaults()` 注册一行**，流水线里再无 `if (format == EPUB)` 分支
- **阅读器 God File 拆分（T-011 / R-11）**：`ui/reader/ReaderScreen.kt`（1326 行 / JaCoCo 645 行）拆成六个文件，**行为零变更**
  - `ReaderScreen.kt`：编排层（状态收集 + 一次性事件分发 + 部件组合），268 行
  - `ReaderContent.kt`：正文渲染（连续滚动）+ 字体应用 `readerTextStyle` + 朗读高亮槽 `paragraphHighlight` + `ChapterFooter` / `ReaderError`
  - `ReaderPager.kt`：翻页（`HorizontalPager`）与双页跨页，收拢原 `PaginatedSpread.kt`（该文件删除）
  - `ReaderChrome.kt`：顶栏 / 底栏 / 目录与书签弹层，收拢原 `ReaderSettingsSheet.kt` 的 `TocSheet`
  - `ReaderEffects.kt`：系统效果（亮度 / 常亮 / 沉浸 / 系统栏图标 / 朗读前台服务生命周期）
  - `SpeechControlBar.kt`：朗读控制条 + 通知权限场景化对话框
- **模块拆分（T-010 / R-12）**：抽出 `:core:domain`（领域模型 + 仓储接口）与 `:core:reader`（阅读引擎）两个**纯 JVM 模块**（只应用 `kotlin("jvm")`，JDK 17 与 app 的 jvmTarget 对齐），`:core:reader` 以 `api` 依赖 `:core:domain`、`:app` 反向依赖两者
  - 分层纪律从「口头约定」变为**编译期强制**：在 `domain.*` / `reader.*` 里写 `import android.*` 直接编译失败
  - **包名保持不变**（`io.github.lunalight.paperread.domain.*` / `.reader.*`），拆分对调用方透明——全仓 import 零改动
  - `reader/BookTextStore.kt` 按依赖边界拆成两个文件：接口（只依赖 `java.io.File` + 领域模型）去 `:core:reader`，`FileBookTextStore`（依赖 `Context.filesDir`）留 `:app`，包名相同故调用方无感
  - 仍留 `:app` 的 Android 相关实现：`FileBookTextStore` / `AndroidXmlParser` / `XmlParsers`（后者的 `android()` 直接构造 `android.util.Xml` 实现，是 `:core:reader` 无法承载的真实边界）；引擎内 XML 解析器缺省值改为 `::JvmXmlParser`，不再反向依赖 app 侧工厂
  - **99 例阅读引擎单测**（Pagination / ReaderProgress / ChapterSplitter / CharsetDetector / ChapterIndexWriter / InlineChapterScanner / TxtChapterStream / EpubParser / XhtmlTextExtractor / JvmXmlParser）迁到 `core/reader/src/test/kotlin` 并**脱离 Robolectric**，在纯 JVM 上跑；CI `unit` 作业补 `:core:domain:test` / `:core:reader:test`（JVM 模块任务名是 `test`，漏写则引擎用例静默不跑）
  - 质量脚本口径同步扩展（代码搬出 app 不等于脱离门禁）：`check-doc-consistency.sh` 的单测总数扫描改扫全部模块测试源码，`check-network-behavior.sh` 的源码扫描与白名单文件定位、`check-r8-keep.sh` 的 `@Serializable` 漂移守卫均覆盖 `core/*/src/main`
- **传输服务线程模型（T-005 / R-10 / G10）**：accept 并发派发 + 落盘即应答，`runBlocking` 清零——大文件传输不再阻塞其他请求
- **Room 迁移基建（T-006 / R-06 / G6）**：书签 schema v1→v2 迁移测试纳入 CI，`fallbackToDestructiveMigration` 保持禁用
- **200MB GBK 大文件验收基建（R-05 / G5）**：`scripts/gen-large-book-sample.sh`（固定 seed、字节级可复现的 200MB GBK 样本生成）+ `LargeBookImportTest`（JVM 侧直测导入流水线，20MB 进 CI、200MB 由属性门控）+ `docs/PERFORMANCE_BASELINE.md`
- **release/R8 运行时冒烟（R-07 / G7）**：CI `r8-smoke` job（`assembleClientRelease` → `scripts/check-r8-keep.sh` 的 mapping 静态断言 → release 变体序列化 round-trip）
- **CI 结构演进**：门禁重组为八个独立作业（`unit` / `robolectric-ui` / `lint` / `room-migration` / `network-audit` / `strings-check` / `assemble` / `r8-smoke`）——失败定位清晰 + flaky 隔离；新增 `scripts/check-doc-consistency.sh`（权限清单 / 覆盖率口径 / 单测总数 / CI 作业数对账）与 `scripts/check-hardcoded-strings.sh`（硬编码中文文案门禁，数据层按 baseline 登记对账）；Robolectric jar 缓存、单测双 fork、纯文档变更跳过 CI
- CONTRIBUTING / README 口径修正（flavor 命名约束、CI 结构、最小权限说明）；`scripts/check-room-migration.sh` 加固；`docs/NETWORK_AUDIT.md` 补「验证机制的能力边界」；隐私页权限名改由 `strings.xml` 提供

### 修复

- **备份恢复不还原「上次阅读时间」（F-01，批次 10 QA 验收）**：`BookRepository.saveReaderPosition` 固定把 `lastReadAt` 写成本次调用的时刻，而共享回写器无法表达「还原历史时间戳」，于是
  1. 备份里的 `lastReadAt` 恢复后一律变成「刚刚」，AC-14.1 的阅读进度保真只剩位置一半；
  2. **更严重**：先恢复旧备份（第 1 章）再恢复新备份（第 3 章）时，第一次恢复已把本地时间抬到 now，第二次的真实新进度被 `WriteBackDecider` 判成「旧进度」而静默跳过回写——报告显示「已存在 1 本、回写 0 本」，用户不会察觉。
  修法：`BookRepository.saveReaderPosition` 增加可选参数 `readAt: Long? = null`（缺省 `System.currentTimeMillis()`，实现零行为变化）→ `BookStateWriter.writeProgress` 透传 → `BackupImporter` 传 `state.lastReadAt`；**迁移侧保持不传**（AC-10.4 原有语义不变）。回归守卫：`BackupRestoreTimestampAuditTest` 两例（真实 Room + 真实导入流水线）由 `@Ignore` 转为常绿。
- **恢复预览缺「版本」与「完整字体引用」**（P3-1，AC-14.8）：副标题改为「备份格式 v1 · 字体：serif、sans（缺失：…，将回退默认字体）」，文案进 `strings.xml`；加密包在口令未提供的预览阶段也能给出缺失字体。
- **导出失败残留 0 字节文件**（P3-2）：`CreateDocument` 已建文档、`copyToDocument` 失败（或用户取消）时未删目标，SAF 上留下空的 `paperread-backup-*.zip`；现在失败 / 取消即清理**本次新建**的目标文档，成功路径不动。
- **UI 口令校验口径与领域层不一致**（P3-3）：UI 只判 `length >= 8`，8 位全空格会通过 UI 校验、到领域层抛 `IllegalArgumentException`，用户只看到泛化的「备份导出失败」。现在 UI 复用 `BackupCrypto.isValidPassword`（新增 `CharSequence` 重载，单一来源），提示改为「口令至少 8 位且不能全为空格」。
- **`BackupEntries.isSafeDirName` 与 KDoc 不符**（P3-4）：改用 ASCII 白名单（原 `Char.isLetterOrDigit()` 按 Unicode 放行中文 / emoji），并补中文 / emoji / 全角 / 空格 / `..` / 超长边界用例。
- **观察项入文档**：条目级 AES-GCM 无 AAD 绑定（同包内可换条目）、明文备份无完整性认证，写入 `SECURITY.md` 新增的「威胁模型：本地备份与恢复」已知限制——当前口径是「防传输损坏与误操作」，不承诺「防主动篡改」。

### 已知问题

- 暂停朗读后恢复会从当前段开头重读一句（系统 TTS 无 pause API，AC-12.2 明示的权衡）
- 系统缺少中文语音数据时听书不可用并提示安装，不静默失败
- TXT 作者恒为「未知」；目录页与正文首章无空行分隔的极端边界可能把首个标题归入目录块（文本不丢失，可激进重解析修复）
- 亮度手动调节仅在阅读器内生效，退出后恢复系统值
- AGP 内置 R8 早于当前 Kotlin 工具链，release 构建存在 Kotlin 元数据解析警告（非致命，保守保留相关类，后续随 AGP 升级消解）
- 搜索 / 大文件的性能红线（首批结果时延、峰值内存）在真机按 `docs/PERFORMANCE_BASELINE.md` 与 `docs/DEVICE_CHECKLIST.md` 验收，仓库内记录的是 JVM 侧近似值

### 升级注意

> **Breaking**：局域网传输协议升级至 v2，与 v1（v1.2 及更早）设备**不互通**；传输需配对鉴权。

- **传输协议 v2 与 v1.2 不互通**：v2.0 起传书 / 换机迁移 / 进度同步启用 v2 协议并要求配对鉴权，v1 报文一律拒绝，新旧版本设备**无法互相发现**。需要互传的两台设备请都升级到 v2.0；每次开放传书需重新配对（配对码当面展示输入，不经网络传输），停止传输服务或取消配对后配对即失效。
- **数据库 schema v1 → v2 自动迁移**：书签新增备注与更新时间两列，升级后首次打开自动完成迁移，无需任何操作；`fallbackToDestructiveMigration` 保持禁用，不丢数据。
- **新旧版本书签互通**：v2.0 的书签备注同步到 v1.2 设备时，旧版忽略未知字段正常合并；v1.2 的书签（无备注）同步到 v2.0 按默认值接纳，不崩溃、不丢书签。
- **权限无新增**：自 v1.2 起权限清单即固定为 4 项（INTERNET + 朗读前台服务三件套），本版本未申请任何新权限；隐私口径见 [PRIVACY.md](PRIVACY.md) 与应用内「隐私说明」页。

[未发布]: https://github.com/lobster-xiaozhua/PaperRead/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/lobster-xiaozhua/PaperRead/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/lobster-xiaozhua/PaperRead/compare/v1.0.1...v1.1.0

## [1.2.0] - 2026-10-20

### 新增
- **TTS 语音朗读（US-12）**：系统 TextToSpeech 离线朗读当前章节，做饭 / 通勤「听书」
  - 入口在阅读器顶栏：从当前可见段开播，段落级高亮并随朗读进度滚动跟随
  - 播放 / 暂停 / 停止 + 上一段 / 下一段步进；系统 TTS 无 pause API，暂停以「记住位置、恢复时重读当前单元」实现
  - 语速 / 音调 0.5 ~ 2.0 滑杆实时调节并持久化；章尾自动续读默认开启（可关），关闭时读完本章即停并提示
  - 超长段按句末标点软切分（单次 speak 受系统约 4000 字符上限约束），逐单元合成推进；朗读中切章不中断，目标章从目标段续读
  - 后台常驻播放：前台服务（mediaPlayback）保活 + 通知栏四按钮控制，锁屏 / 切后台不中断
  - 系统缺少中文语音数据时明确提示安装，不静默失败；全程零新增依赖、零网络请求（语音本机合成）
- **阅读进度同步（US-11）**：两台设备之间双向交换阅读位置与书签，换设备继续读无需重新找位置
  - 交换方式：本机拉起 `GET /sync/state` 拉取对端快照 → 本地合并 → `POST /sync/apply` 把对端缺的那部分回推，一次同步双向收敛
  - 进度冲突按「最后阅读时间较新者胜」处理，且**覆盖与回推互斥**——同一轮同步里不会出现两台设备互相覆盖
  - 书签按「章节 + 段落 + 字符偏移」识别同一条，合并**只增不删**：任何一侧独有的书签都会保留并回推给对侧
  - 两侧书目不一致时只计数不搬运（对端另有 N 本 / 本地另有 N 本），不删除、不擅自导入
  - 同步完成后一次性告知：覆盖本地进度 N 本、新增书签 N 条、回推 N 本
- **换机迁移（US-10）**：「迁移整库」把全部书籍源文件与阅读状态（进度 / 书签）打包为单一 zip 发到对端，在新设备上继续阅读
  - 接收端逐本导入并复用既有导入流水线；任意单本失败（DRM / 损坏 / 缺源）只记入失败清单，不中断整体迁移，结束展示成功 / 失败明细
  - 进度与书签按「最后阅读时间较新者胜」回写；内容已存在（SHA-256 命中）的书不重复导入，但仍按规则回写更新的一方
  - 迁移包解包带路径穿越防护（可疑条目丢弃而不中断），损坏包 / 高版本清单明确报错
- **局域网传书（US-9）**：同一 WiFi 下把书架多选的书籍直接传到另一台设备的纸阅，不依赖数据线 / 云盘
  - UDP 广播发现：报文含协议魔数 / 版本 / 设备名 / HTTP 端口，广播才应答（防乒乓），3 秒内可被发现，超时自动下线
  - 零新增依赖的 HTTP 传输（JDK 原生 ServerSocket / HttpURLConnection）：`GET /meta` + `POST /upload`，服务仅绑定 WiFi 网卡地址，不监听其他接口（不访问公网）
  - 书架多选 → 传书页选择在线设备 → 逐本发送：进度（字节比 + 序号）实时可见、随时可取消，失败携带原因并清理现场
  - 接收端落盘后经 FileProvider 走与本地导入**完全相同**的流水线（SHA-256 去重 / 编码与切分策略可调 / 待确认），不出现第二条导入逻辑
  - 设备标识为随机生成并持久化的 UUID（`filesDir/transfer/device_id.txt`），不使用 MAC / IMEI / ANDROID_ID
- **关于页**：应用名与版本号、隐私承诺（最小权限：INTERNET + 朗读前台服务三件套，分别用于局域网传输与朗读后台保活，不连接互联网）、Apache-2.0 许可全文、GitHub 主页与 Issue 反馈（系统浏览器打开，无内置 WebView）、无第三方依赖声明与维护者信息
- **设置页接入阅读偏好**：主题 / 字体 / 字号 / 行距 / 段距 / 页边距 / 亮度 / 屏幕常亮 / 沉浸模式 / 音量键翻章，与阅读器底部弹层共用同一套控件与数据源，实时生效并自动持久化
- **仿真翻页（C7）**：阅读器新增「翻页模式」开关（默认关闭，与连续滚动共存）——开启后正文改为左右滑动翻页，配书页边缘阴影模拟翻页质感；点按左 / 中 / 右三区分别上一页 / 唤出工具栏 / 下一页，音量键亦可翻页；越章边界自动衔接上下章
  - 分页由引擎纯函数 `paginate`（段边界切页，零 Android 依赖、纯 JVM 单测覆盖）配合 UI 层 `TextMeasurer` 段落测高完成，页码是 UI 层概念、**进度仍走既有 `ReaderPosition`**（按页起始段折算字符偏移），不新增进度字段、不破坏段落级进度恢复
  - 翻页模式默认关闭、滚动为开箱体验；开关与阅读器底部弹层共用同一套控件（AC-8.3）

### 变更
- **权限策略调整（2026-10-19 用户拍板）**：TTS 朗读选后台常驻播放，主包在 INTERNET 之外追加前台服务三件套（`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `POST_NOTIFICATIONS`）——前台服务为系统对后台播放的强制要求，通知权限被拒时播放仍可继续（仅通知不可见）；除此之外不申请任何权限，关于页文案、README 与 `AboutScreenTest` 权限断言同步改为新口径
- **权限策略调整（2026-10-18 用户拍板）**：允许申请权限、保持最小权限——主包声明唯一权限 `INTERNET`（Android 无「仅局域网」权限，任何 socket 均需声明），用于同一局域网内的设备发现与书籍 / 状态传输，不访问公网；原「完全离线 / 不申请网络权限」承诺相应更新，关于页文案与 `AboutScreenTest` 权限断言改为最小权限口径
- **分发 flavor 拆分**：新增 `client`（客户端正式版，唯一对外交付）与 `dev`（调试版，applicationId 后缀 `.dev`、可同机安装、`BuildConfig.IS_DEBUG_BUILD`）两个 flavor；CI 双变体测试 + 双 APK 产物，覆盖率门禁绑定 client 变体（AGP 禁止 flavor 与 build type 同名，调试版 flavor 取名 `dev`）
- **Kotlin 工具链升级 2.0.21 → 2.2.20**（配套 KSP 2.2.20-2.0.4、Room 2.8.5）：解锁 AndroidX / kotlinx 新版依赖（其 class 文件 metadata 为 Kotlin 2.2 编译，与 2.0.21 工具链不兼容，此前 Dependabot 的 minor 更新全部飘红）。AGP 8.7.3 内置 R8 早于 Kotlin 2.2.20，release 构建存在 Kotlin 元数据解析警告（非致命，保守保留相关类），后续随 AGP 升级消解

### 基础设施
- 覆盖率门禁落地（JaCoCo 行覆盖率 ≥ 0.80，CI 强制执行）
- CI 增强：wrapper 校验、concurrency 取消、测试报告与覆盖率报告上传
- CI 提效：门禁任务合并为单次 Gradle 调用（省 3 次配置阶段）；Robolectric android-all jar（约 335MB/轮）经 actions/cache 跨运行复用；单测双 fork 并行；纯文档变更跳过 CI；产物保留期收敛为 14 天
- Dependabot 策略：gradle 生态仅 patch、actions 生态忽略大版本（Kotlin 锁定期收敛 churn）

### 新增
- **平板适配（C6）**：`androidx.window` 提供 WindowSizeClass；阅读器在 Expanded 大屏（平板/横屏）自动启用真·分页双页跨页（左右两页、点按翻页），共用 `ReaderPosition` 进度口径，旋屏保位置；Compact/Medium 仍单栏连续滚动
- **双页开关**：阅读设置新增「双页跨页」开关（`twoPageSpread`，默认开启，手机竖屏自动忽略）
- **响应式布局**：书架网格列数随屏宽自适应（`GridCells.Adaptive`）；宽屏单栏限最佳行宽并居中；导入/重解析/判重弹层限宽居中（详见 [SPEC](docs/SPEC.md) US-13）

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
