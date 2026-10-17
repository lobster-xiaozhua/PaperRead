# PaperRead 路线图（v1.1 → v2.0）

> 制定依据：v1.0.1 审计报告（综合 8.3/10）· 当前 151 单测全绿 / lint 0/0 / 总行覆盖 89.7%（B1、B2、B3 已完成，B1 待真机验收）
> 原则：先发布收尾，再质量收敛，最后做体验增量；隐私红线（零网络权限）不变更

---

## 总览

```
现在 ──► A 发布收尾（0.5 天，阻塞）
          └─► B v1.1 质量收敛（约 1.5 周）
                └─► C v1.2 体验增强（约 2–3 周）
                      └─► v2.0 平台化（待定）
```

| 版本 | 主题 | 目标 | 验收 |
|---|---|---|---|
| A | 发布收尾 | 代码上 GitHub，CI 首次全绿，v1.0.1 Release | 仓库公开可访问；Actions 3 项 pass；tag 就位 |
| v1.1 | 质量收敛 | 清掉审计缓办项，自动化回归网补齐 | 单测 ≥110、androidTest ≥5、reader+data 覆盖率 ≥80% |
| v1.2 | 体验增强 | 让「读得舒服」再上一个台阶 | 搜索/朗读/字体三项可用，装机清单通过 |
| v2.0 | 平台化 | 多端/多语言/生态能力 | 待需求验证后另立项 |

---

## A. 发布收尾（0.5 天 · 阻塞中）

| # | 事项 | 负责 | 说明 |
|---|---|---|---|
| A1 | GitHub 连接器重新授权（勾选 repo 权限） | **用户** | 当前 Token 零作用域，建仓/推送均 403 |
| A2 | 建仓 `lobster-xiaozhua/PaperRead`（public）并推送 main | 我 | 7 个提交待推（dc70126 之后含 3ecac68） |
| A3 | 修正 README clone 地址为实际账户 | 我 | 现写作 `lunalight`，与实际授权账户不一致 |
| A4 | 打 tag `v1.0.1` + GitHub Release 附两个 APK | 我 | Release notes 直接用 TASKS 的 v1.0.1 修复记录 |
| A5 | 首次 CI 运行验证（test/lint/assemble 三 job） | 我 | 观察 Actions 日志，修正任何环境差异 |
| A6 | 仓库元信息：topics、Issue 模板、README badge | 我 | topics: android / kotlin / jetpack-compose / ebook-reader / offline-first |

**风险**：CI 首次跑 Robolectric 需从 Maven Central 拉 137 MB android-all jar，冷启动约 5–8 分钟；若超时需加缓存 action。

---

## B. v1.1 质量收敛（约 1.5 周）

### B1 导入流式解码（2 天）· 最高优先级 ✅ 已完成
- 现状（修复前）：`readText()` 全量驻留，200 MB 书导入峰值内存约 400 MB
- 方案：`CharsetDecoder` 分块解码 → 增量写 `book.txt` → 边切章边记录双偏移（`TxtChapterStream`，约 490 行；强制分段三段语义与批量逐段一致）
- 顺带修复：编码探测对 64 KB 前缀截断的误判（合法 UTF-8 被回落 GB18030 → 乱码 → 章节数暴跌）
- 单测层验收：115 单测全绿 + lint 0/0；20 MB 冒烟流式核心常驻 13 MB（与文件大小无关）；5 MB GBK 章节数 > 5000
- **待真机验收**：200 MB GBK TXT 导入成功；`dumpsys meminfo` 峰值 < 100 MB；进度条仍平滑（DEVICE_CHECKLIST 第 7 节）
- 详见 TASKS.md「B1 导入流式解码」

### B2 ViewModel 自动化测试（1 天）✅ 已完成
- 栈：`kotlinx-coroutines-test` + Turbine + 手工 fake repository
- 覆盖：500ms 防抖保存（推进虚拟时间验证落库次数）、切章立即落库、书签增删、导入取消状态机、判重替换流程
- 落地：三接口抽取（BookTextStore/SettingsStore/BookImporter）+ 4 个 fake；ReaderViewModel 12 用例 + BookshelfViewModel 19 用例；全量 146 单测全绿
- 验收：ReaderViewModel 行覆盖 100%（104/104）、BookshelfViewModel 100%（63/63），≥70% 达标（`./gradlew :app:jacocoTestReport`）
- 详见 TASKS.md「B2 ViewModel 自动化测试」

### B3 Compose UI 冒烟测试（1–2 天）✅ 已完成
- 栈：Robolectric + `compose-ui-test-junit4`（`@GraphicsMode(NATIVE)`，`createComposeRule` 无需真机，随 `testDebugUnitTest` 进 CI）
- 覆盖：空书架 → 导入入口渲染；书架渲染 + 点封面打开；阅读器点击正文工具栏显隐；设置面板拖字号滑块（UI 回流）；书签 Tab 空态
- 落地：有状态屏幕走真实 AppContainer（Room/文本存储落在 Robolectric 文件系统），无状态弹层受控参数直测
- 验收：5 条冒烟用例全绿；全量 151 单测；总行覆盖 89.7%
- 详见 TASKS.md「B3 Compose UI 冒烟测试」

### B4 进度口径统一（0.5 天）
- `paragraphsOf` 的 trim/空行过滤与 `chapterCharOffset` 的原文累加对齐（以原文偏移为准，段落携带原始起点）
- 验收：长书首尾进度误差 < 0.05%

### B5 备份策略定案（0.5 天）
- `allowBackup=true` 会把书库带上云。二选一：
  - 方案甲（推荐）：`dataExtractionRules` 仅备份设置，书籍目录排除
  - 方案乙：关闭备份，后续做「本地导出/导入」替代
- 验收：`adb backup` 不含 `files/books/`

### B6 拆分 `:reader` 模块（1 天，可选）
- 触发条件：reader 包代码 >1,500 行或需要被第二个 app 复用；当前 936 行，**建议暂缓**
- 收益：编译增量、依赖边界硬约束；成本：迁移 + 测试路径调整

### B7 失败原因结构化（0.5 天）
- 导入/解析错误改为 `sealed class ImportError`（含 stage + cause），UI 映射用户话术
- 验收：DRM/空间不足/编码失败三类错误在结果页文案可区分（当前已部分满足）

---

## C. v1.2 体验增强（按用户价值排序）

| # | 功能 | 价值 | 成本 | 隐私影响 | 建议 |
|---|---|---|---|---|---|
| C1 | 全书搜索（关键词 + 结果定位到段） | ★★★★★ | 2 天 | 无 | **做**（双偏移天然支持） |
| C2 | TTS 朗读（系统 TTS，离线） | ★★★★ | 2 天 | 无 | **做**（需处理章节续播） |
| C3 | 自定义字体（导入 ttf/otf） | ★★★★ | 1 天 | 无 | **做**（SAF 选文件 + 字体缓存） |
| C4 | 书签备注 + 按章分组 | ★★★ | 1 天 | 无 | 做 |
| C5 | 本地备份/恢复（zip 导出导入） | ★★★ | 1.5 天 | 无 | 做（配合 B5） |
| C6 | 平板双栏 / 横屏优化 | ★★★ | 2 天 | 无 | 做（WindowSizeClass） |
| C7 | 仿真翻页动效 | ★★ | 3 天 | 无 | 视反馈，优先覆盖式简单翻页 |
| C8 | 国际化（英文） | ★★ | 1.5 天 | 无 | 有海外反馈再做 |
| C9 | Wi-Fi 传书 | ★★★ | 3 天 | **需 INTERNET 权限** | **不做**（破坏零网络卖点；如需，另立独立 flavor 并在 README 显著标注） |

**推荐 v1.2 范围**：C1 + C2 + C3 + C5（约 6.5 天），C4/C6 视时间追加。

---

## D. v2.0 待验证方向（不进当前计划）

- 多书源格式：Markdown / HTML 单文件导入
- 阅读统计（时长、字数、连续天数，全本地）
- 无障碍：TalkBack 全链路、动态字号（系统 200%）
- 桌面端（Compose Multiplatform）可行性调研

---

## 里程碑节奏建议

| 周 | 内容 | 出口 |
|---|---|---|
| W1 | A 发布收尾 + B1 流式导入 | GitHub 可访问；200MB 导入通过 |
| W2 | B2 + B3 测试补齐 | 单测 ≥110、UI 冒烟 5 条进 CI |
| W3 | B4–B7 + v1.1 Release | v1.1 tag + Release |
| W4–W5 | C1 搜索 + C2 TTS + C3 字体 | v1.2-beta |
| W6 | C5 备份 + 回归 + v1.2 Release | 装机清单全通过 |

---

## 决策点（需用户拍板）

1. **GitHub 账户**：确认推到 `lobster-xiaozhua/PaperRead`，还是先改连接器授权到目标组织？
2. **备份策略**：B5 方案甲（只备份设置）还是乙（关闭备份）？
3. **v1.2 范围**：C1/C2/C3/C5 四项是否照单全收？
4. **Wi-Fi 传书**：确认放弃（保持零网络），还是接受独立 flavor？
