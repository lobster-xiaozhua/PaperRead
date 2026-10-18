# PaperRead 开发工单与项目状态

> 每个工单 ≤ 4 小时。退出标准：可运行 APK + `./gradlew testClientDebugUnitTest testDevDebugUnitTest lint assembleDebug` 全绿。
> 协作规则见 [AGENTS.md](AGENTS.md)；验收标准见 [SPEC.md](SPEC.md)；未来规划见 [ROADMAP.md](ROADMAP.md)。
> 本文件只记录**当前状态**：已具备的能力 + 待办/未决项，历史逐条工单已折叠。

## 当前版本

- **最新发布**：v1.1.0（versionCode 3，质量收敛版：流式导入、结构化失败原因、备份策略定案）
- **main 未发布变更**：Kotlin 工具链 2.0.21 → 2.2.20（配套 KSP 2.2.20-2.0.4、Room 2.8.5），解锁新版依赖图
- **质量基线**：185 单测全绿、lint 0 error / 0 warning、行覆盖率约 87%、CI 双门禁（Debug + Release）全绿

## 当前能力（已完成）

### 工程与交付
- Compose + Material 3 + 类型安全导航脚手架；Gradle 版本目录
- CI 门禁：`testClientDebugUnitTest` + `testDevDebugUnitTest` + `lint` + `assembleDebug` + `assembleRelease`（R8）+ JaCoCo 行覆盖率 ≥ 80%，强制全绿方可合并
- CI 提效（2026-10-18）：四门禁任务合并为单次 Gradle 调用（省 3 次配置阶段）；Robolectric android-all jar（API 34 运行 SDK + API 35 compileSdk，约 335MB/轮）经 actions/cache 跨运行复用；单测双 fork 并行；纯文档变更（md / docs）跳过 CI；产物保留期收敛为 14 天
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

### 备份与测试
- 备份策略（方案甲-B）：云备份排除书库目录（`books/`），换机（D2D）保留整库；双 XML 规则覆盖 Android 11–12+
- 测试：JVM 单测 + Turbine ViewModel 测试 + Robolectric Compose UI 冒烟 + JaCoCo 覆盖率度量

## 规划工单（T 系列，局域网传输）

> 来源：用户 2026-10-18 拍板（ROADMAP C9 决策点 2）——项目允许申请权限、保持最小权限：INTERNET 进主包（Android 无「仅局域网」权限，任何 socket 均需声明），传输能力进主包；分发拆 client（客户端正式版）/ dev（调试版，可同机安装）两个 flavor。验收标准见 SPEC 第 7、9 节（US-9 / US-10 / US-11）。
> 门禁：`./gradlew testClientDebugUnitTest testDevDebugUnitTest lint assembleDebug` 全绿；应用除 INTERNET 外不得持有任何权限。

### T1 flavor 拆分与最小权限（约 1.5h）

- 涉及：`app/build.gradle.kts`（flavorDimensions + client/dev，后者 applicationIdSuffix `.dev` / versionNameSuffix `-dev` / IS_DEBUG_BUILD 标记）、主 `AndroidManifest.xml`（唯一权限 INTERNET）、jacoco 任务改绑 clientDebug、CI 任务名与 APK 上传路径 flavor 化、关于页权限文案与 `AboutScreenTest` 权限断言改为最小权限口径
- 约束：除 INTERNET 外零权限；client 为唯一对外交付版；dev 与 client 可同机安装
- 验收：SPEC AC-7.1 – AC-7.4；CI 两 flavor 测试 + 覆盖率门禁 + lint + 双 APK 产物全绿

### T2 传书核心（约 4h）

- 涉及：`app/src/main/java/.../transfer/`——UDP 发现（广播/响应编解码，纯 JVM 可测）、极简 HTTP 服务（JDK ServerSocket，仅绑定 WiFi 网卡地址）、书架多选发送入口、接收端落盘 + FileProvider 复用现有导入流水线
- 约束：零新增第三方依赖（JSON 用已引入的 kotlinx.serialization）；传输核心纯 JVM 单测覆盖（localhost 真实 socket）；UI 层不直连 socket
- 验收：SPEC AC-9.1 – AC-9.7

### T3 换机迁移（约 3h）

- 涉及：整库迁移包（书籍 source + 进度/书签 JSON，zip）打包 / 解包 / 逐本导入 / 按 SHA-256 回写进度书签
- 约束：单本失败不中断整体；回写按「最后阅读时间较新者胜」
- 验收：SPEC AC-10.1 – AC-10.4

### T4 进度同步（约 3h）

- 涉及：双向阅读状态交换、设备标识（随机 UUID 持久化，禁硬件标识）、last-write-wins 冲突解决、单侧数据合并
- 约束：冲突解决逻辑纯 JVM 单测覆盖；书签合并不得删除对侧独有数据
- 验收：SPEC AC-11.1 – AC-11.4

## 待办 / 未决项

### 真机验收（非阻塞，代码已合入）
- B1：200 MB GBK TXT 导入峰值内存 < 100 MB、进度平滑（`dumpsys meminfo` 验证）
- B5：设备侧备份验收（Android 11 `adb backup` 不含 `books/`；Android 12+ `bmgr backupnow`）

### v1.2 体验增强（范围待定）
> 见 ROADMAP C 段，下列为候选，隐私红线（零网络权限）不变更

- C1 全书搜索（关键词 + 定位到段）· C2 TTS 朗读（系统 TTS，离线）· C3 自定义字体（导入 ttf/otf）
- C4 书签备注 + 按章分组 · C5 本地备份/恢复（zip 导出导入）· C6 平板双栏/横屏优化
- C7 仿真翻页动效 · C8 国际化（英文）· C9 Wi-Fi 传书（**已拍板：最小权限 + client/dev 双 flavor，见 T 系列工单**）

### v2.0 方向（待需求验证）
- Markdown / HTML 单文件导入 · 阅读统计（全本地）· 无障碍（TalkBack / 动态字号）· 桌面端可行性

### 架构暂缓
- B6 拆分 `:reader` 为独立 Gradle 模块（待 reader 包代码量增长再评估）

### 未发布技术债
- AGP 升级消解 R8 对 Kotlin 2.2 元数据的解析警告（当前非致命，保守保留类）
- 已知限制：TXT 作者恒为「未知」；目录页与正文首章无空行分隔的极端边界；亮度手动调节仅阅读器内生效

### 待用户拍板
- v1.2 范围：C1/C2/C3/C5 是否照单全收
