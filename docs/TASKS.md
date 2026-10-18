# PaperRead 开发工单与项目状态

> 每个工单 ≤ 4 小时。退出标准：可运行 APK + `./gradlew testDebugUnitTest lint assembleDebug` 全绿。
> 协作规则见 [AGENTS.md](AGENTS.md)；验收标准见 [SPEC.md](SPEC.md)；未来规划见 [ROADMAP.md](ROADMAP.md)。
> 本文件只记录**当前状态**：已具备的能力 + 待办/未决项，历史逐条工单已折叠。

## 当前版本

- **最新发布**：v1.1.0（versionCode 3，质量收敛版：流式导入、结构化失败原因、备份策略定案）
- **main 未发布变更**：Kotlin 工具链 2.0.21 → 2.2.20（配套 KSP 2.2.20-2.0.4、Room 2.8.5），解锁新版依赖图
- **质量基线**：185 单测全绿、lint 0 error / 0 warning、行覆盖率约 87%、CI 双门禁（Debug + Release）全绿

## 当前能力（已完成）

### 工程与交付
- Compose + Material 3 + 类型安全导航脚手架；Gradle 版本目录
- CI 门禁：`testDebugUnitTest` + `lint` + `assembleDebug` + `assembleRelease`（R8）+ JaCoCo 行覆盖率 ≥ 80%，强制全绿方可合并
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

### 备份与测试
- 备份策略（方案甲-B）：云备份排除书库目录（`books/`），换机（D2D）保留整库；双 XML 规则覆盖 Android 11–12+
- 测试：JVM 单测 + Turbine ViewModel 测试 + Robolectric Compose UI 冒烟 + JaCoCo 覆盖率度量

## 待办 / 未决项

### 真机验收（非阻塞，代码已合入）
- B1：200 MB GBK TXT 导入峰值内存 < 100 MB、进度平滑（`dumpsys meminfo` 验证）
- B5：设备侧备份验收（Android 11 `adb backup` 不含 `books/`；Android 12+ `bmgr backupnow`）

### v1.2 体验增强（范围待定）
> 见 ROADMAP C 段，下列为候选，隐私红线（零网络权限）不变更

- C1 全书搜索（关键词 + 定位到段）· C2 TTS 朗读（系统 TTS，离线）· C3 自定义字体（导入 ttf/otf）
- C4 书签备注 + 按章分组 · C5 本地备份/恢复（zip 导出导入）· C6 平板双栏/横屏优化
- C7 仿真翻页动效 · C8 国际化（英文）· C9 Wi-Fi 传书（**维持零网络，不做或独立 flavor**）

### v2.0 方向（待需求验证）
- Markdown / HTML 单文件导入 · 阅读统计（全本地）· 无障碍（TalkBack / 动态字号）· 桌面端可行性

### 架构暂缓
- B6 拆分 `:reader` 为独立 Gradle 模块（待 reader 包代码量增长再评估）

### 未发布技术债
- AGP 升级消解 R8 对 Kotlin 2.2 元数据的解析警告（当前非致命，保守保留类）
- 已知限制：TXT 作者恒为「未知」；目录页与正文首章无空行分隔的极端边界；亮度手动调节仅阅读器内生效

### 待用户拍板
- v1.2 范围：C1/C2/C3/C5 是否照单全收
- Wi-Fi 传书：确认放弃（保持零网络）还是接受独立 flavor
