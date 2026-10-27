# 性能基线（PERFORMANCE_BASELINE）

> 需求：R-05（G5）「200MB GBK 大文件导入」首批性能基线。
> 关联：[DEVICE_CHECKLIST.md §7](DEVICE_CHECKLIST.md)（真机记录表）、
> [SPEC.md](SPEC.md)、`app/src/test/java/io/github/lunalight/paperread/reader/LargeBookImportTest.kt`。
>
> **本文件的口径纪律**：沙箱（无 KVM、无模拟器、无真机）只能测 JVM 侧数值，
> 与真机的 ART / PSS 不可换算。凡未在真机测过的单元格一律写「待真机补测」，
> 不填估算值、不用 JVM 数值冒充真机数值。

## 1. 两栏基线

### 1.1 沙箱自动化口径（已实测，可复现）

测量环境：容器内 `ubuntu` + OpenJDK 20 + Gradle 8.9，`--max-workers=1`，
测试 JVM `-Xmx2g`，Robolectric `@Config(sdk=[34])`，导入走**真实流水线**
`DefaultBookImporter.prepare()`（流式拷贝 + SHA-256 → 编码探测 → `TxtChapterStream`
→ `book.txt` 落盘），非 mock。

| 指标 | 20MB GBK 样本 | 200MB GBK 样本（脚本产物） | 200MB GBK 样本（测试内生成） | 采集方式 |
|------|---------------|---------------------------|-----------------------------|---------|
| 章节数（导入结果） | 9,279 | 93,428 | 92,702 | `pending.chapters.size` |
| 章节数（独立逐行扫描） | 9,279 | 93,428 | 92,702 | `GbkSampleBookGenerator.referenceChapterCount()`，与导入实现无共享代码 |
| 章节数一致性 | ✅ 相等 | ✅ 相等 | ✅ 相等 | 断言「生成器计数 = 参考实现 = 导入结果」 |
| 全文字符数 | 10,721,992 | 108,144,063 | 107,303,533 | `pending.totalChars` |
| 源文件大小 | 20,972,971 B | 211,358,656 B | 209,716,074 B | `pending.fileSize` |
| `book.txt` 大小 | 31,223,950 B | 314,573,249 B | 312,128,615 B | `File.length()`；脚本样本 = `meta.UTF8_BYTES - 1`（末行无换行） |
| 导入耗时 | 553 ms | 4,045 ms（testcase 计时） | 4,462 ms | `System.nanoTime()` 包住 `prepare()` / JUnit testcase time |
| 堆占用（导入前） | 237 MB | — | 151 MB | `totalMemory - freeMemory` |
| 堆占用（20ms 采样峰值） | 260 MB | — | 735 MB | 采样线程取 `totalMemory - freeMemory` 峰值 |
| 首章解码 | 无 `�`，`第1章 荒原第1节` | 无 `�`，与 `meta.FIRST_LINE` 一致 | 同左 | `readChapter(首章)` |
| 末章解码 | 无 `�`，末行不以 `\n` 结尾 | 无 `�`，与 `meta.LAST_LINE` 一致 | 同左 | `readChapter(末章)` |
| 双偏移连续性 | ✅ 9,279 章逐章校验 | ✅ 93,428 章逐章校验 | ✅ 92,702 章逐章校验 | `charStart/charEnd`、`byteStart/byteEnd` 首尾相接；末章 `byteEnd == book.txt 长度` |
| `book.txt.tmp` 残留 | 0 | 0 | 0 | 断言临时文件不存在 |

> 表中数值为**同一轮**运行（2026-10-26，容器内 JDK 20 / `-Xmx2g`）的单次实测。
> 堆占用与耗时逐轮有波动（另一次运行 20MB 为 745ms / 峰值 389MB，200MB 为
> 4,569ms / 峰值 690MB），属 GC 时机与机器负载差异，**不作门禁**（见 §4）。
> 「20MB 样本」与「200MB 样本（测试内生成）」由同一生成器按不同目标体积产出，
> 故章节数可按体积比交叉核对：9,279 × 10 = 92,790 vs 实测 92,702（差 0.09%，
> 源于末章按整章装箱的取整）。

复现命令（沙箱内，约 20s）：

```bash
# 1) 生成可复现样本（固定 seed，字节级可复现：同一 seed 的 SHA-256 一致）
./scripts/gen-large-book-sample.sh --size-mb 200 --out /tmp/large-book-200mb-gbk.txt

# 2) 中等样本（CI 默认，20MB）+ 200MB 用例 + 脚本产物核对
./gradlew testClientDebugUnitTest --tests "*LargeBookImportTest*" \
  -Ppaperread.largeBook.200mb=true \
  -Ppaperread.samplePath=/tmp/large-book-200mb-gbk.txt \
  --console=plain --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" \
  -Probolectric.dependency.repo.url=https://maven.aliyun.com/repository/public
```

默认 CI 只跑 20MB 那条（`-Ppaperread.largeBook.200mb` 缺省关闭）：200MB 用例需要
约 1GB 临时磁盘与 5s CPU，在共享 runner 上属纯浪费，移交发版前手动执行。

### 1.2 待真机补测项（沙箱不可执行）

真机项**全部**依赖设备（install + 交互 + `dumpsys`），沙箱内无 KVM、无模拟器、
无真机，一项都跑不了。判据与填写位置见 [DEVICE_CHECKLIST.md §7.3](DEVICE_CHECKLIST.md)。

| 指标 | 目标值 | 测量方法 | 状态 |
|------|--------|---------|------|
| 导入成功、无 OOM / ANR | 成功且无 `E/AndroidRuntime` / `ANR in …` | `adb logcat` 全程抓取 | 待真机补测 |
| 进度对话框流畅度 | 任意两次刷新间隔 < 2s | 录屏逐帧复核 | 待真机补测 |
| 峰值 PSS | < 100 MB | `adb shell dumpsys meminfo io.github.lunalight.paperread \| grep "TOTAL PSS"` 周期采样取峰值 | 待真机补测 |
| 章节数与 PC 端一致 | = 93,428（200MB 脚本样本） | 书架章节数 vs `meta.CHAPTERS` | 待真机补测 |
| 首/末章无乱码 + 末章进度 | 无 `�`；末章进度 ≈ 100% 且重进一致 | 目视 + 退出重进 | 待真机补测 |
| `book.txt` 大小对齐 | = `meta.UTF8_BYTES - 1` = 314,573,249 B | `adb shell stat -c %s files/books/<dir>/book.txt` | 待真机补测 |
| 冷启动到书架可交互 | 首版无硬指标，记录实测值 | `am start -W`（`TotalTime`） | 待真机补测 |
| 翻章 / 滚动帧率 | 首版无硬指标，记录实测值 | `dumpsys gfxinfo` + `framestats` 分位 | 待真机补测 |

> AC 口径提醒：G5 的验收结论**只能**由 §7.3 六项全绿得出。本节 1.1 的
> JVM 数值只能证明「切分正确、无乱码、偏移自洽、无残留」，证明不了「不 OOM / 不 ANR /
> PSS < 100MB」——那三项是运行时与系统内存行为，必须真机。

### 1.3 全书搜索（AC-15.6）沙箱近似 + 真机待测

AC-15.6 的三条时延（首批结果 ≤ 5s / 结果点击到定位 ≤ 1s / 取消 ≤ 500ms）是
**API 34 中端机口径**，沙箱（无 KVM / 无模拟器 / 无真机）一条都测不了。
能做的是：把「同一份实现」放进 JVM 探针，量出**与真机同向**的近似值，
供改动前后横向自比，并如实标注不可换算。

沙箱探针：`core/data/src/test/java/.../data/search/SearchPerformanceProbeTest.kt`
（200 章 × 50,000 字 ≈ 1,000 万字，UTF-8 约 30MB 量级；关键字分别落在第 0 章与
第 100 章。实现走真实的 `CurrentBookSearchSource` + `SearchText`，不是 mock）。

| 指标 | 沙箱实测（2026-10，容器 JDK 20 / `-Xmx1536m`） | 真机（API 34 中端机）目标 | 状态 |
|------|---------------------------------------------|--------------------------|------|
| 10M 字正文首批结果耗时 | 30 ms | —（沙箱近似） | 已实测 |
| 10M 字正文全量扫描耗时 | 255 ms | —（沙箱近似） | 已实测 |
| 首批 / 全量之比 | ≈ 12%（第 0 章即命中） | — | 已实测，验证流式有效 |
| 100MB TXT 首批结果 ≤ 5s | 不可测 | ≤ 5s | **待真机补测** |
| 结果点击到段落定位 ≤ 1s | 不可测（无渲染与主线程） | ≤ 1s | **待真机补测** |
| 取消响应 ≤ 500ms | 不可测（无 UI 事件链） | ≤ 500ms | **待真机补测** |
| 搜索峰值内存不随书体积增长 | 逻辑保证：逐章读取 + 单章处理完即弃（`CurrentBookSearchSource` 注释） | 200MB 书 PSS < 100MB 红线内 | **待真机补测** |

口径提醒：
1. 表中 30ms / 255ms 是「同一进程内、数据已驻留堆」的纯 CPU + 字符串匹配耗时，
   不含真机的磁盘 IO（`readChapter` 走 `RandomAccessFile` 定位 + 解码）、
   主线程调度、Compose 重组与首帧绘制——真机首批结果必然比 30ms 慢，且慢多少
   无法从 JVM 数值外推；
2. 「不随书体积增长」是**实现结构**（逐章流式、无索引缓存）的性质，沙箱用「峰值
   只与单章大小同阶」论证，PSS 仍需真机 `dumpsys meminfo` 取证；
3. 取消响应包含「协程取消 → 停止读盘 → UI 状态回落」三段，沙箱只证明了第一二段
   （`take(n)` 后不再 `readChapter`，见 `CurrentBookSearchSourceTest` 的取消用例）。

复现命令（沙箱内，约 5s）：

```bash
./gradlew :core:data:testClientDebugUnitTest --tests "*SearchPerformanceProbeTest*" \
  --console=plain --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
# 数值在 build/test-results/testClientDebugUnitTest/TEST-*SearchPerformanceProbeTest.xml 的 system-out 里
```

## 2. 口径差异（为什么 JVM 数字不能当 PSS 用）

| 维度 | 沙箱 JVM（本文件 §1.1） | 真机 Android（§1.2） |
|------|------------------------|---------------------|
| 内存指标 | 堆已用量 `totalMemory - freeMemory`（含**未回收垃圾**；未强制 GC） | PSS：整进程物理内存（含 ART 堆、native、code、栈、共享库分摊） |
| 上限 | `-Xmx2g` 分代堆，超限 `OutOfMemoryError` | 设备 dalvik 堆上限 + 系统 lowmemorykiller 杀进程 |
| 卡顿判据 | 无（JVM 侧不渲染） | 主线程 5s ANR / 帧间隔丢帧 |
| 存储 | ext4/overlay，无 FUSE | SAF / FUSE，双份存储（source + book.txt） |
| 结论 | 只能横向自比（改动前后） | 唯一可作为 G5 验收的数字 |

因此 §1.1 的「采样峰值 260~735MB」**不代表**真机会占同样内存，也不代表会超
100MB——它只说明该 JVM 配置下大文件导入的**分配量级**（含尚未回收的垃圾）；
反过来，JVM 上通过也不能推出真机 PSS 达标。两侧都记录、互不替代。

## 3. 基线解读与观察

1. **章节切分与体积无关**：20MB → 9,279 章、200MB → 92,702 章，比值与体积比
   （≈10.0×）一致，说明流式状态机没有「随文件增长而少切/多切」的漂移；
   独立参考实现（逐行 GBK 解码 + 正则计数）三方一致。
2. **耗时近线性**：553ms → 4,462ms（体积 10.0×，耗时 8.1×，略低于线性——
   固定开销（Robolectric 环境、首块解码器初始化）在大文件上被摊薄），
   无超线性劣化，与「单遍解码 + 单遍切分」的实现预期相符。
3. **内存与文件大小无关**：`TxtChapterStream` 滚动缓冲 ≤ 256KB，正文不再整本驻留；
   采样峰值随章节**数量**（`Chapter` 对象 + 候选列表）增长，不随**字节数**增长。
   这是 200MB 可导入的根因，也是「章节数极多（9 万章）时内存仍有上界」的依据。
4. **末章边界**：两个 200MB 样本都以「正文结尾即 EOF」构造，导入后
   `末章.byteEnd == book.txt 长度` 且末字节非 `0x0A` —— 覆盖了历史上最容易错的
   一字节差。
5. **已知抖动**：本批次另记 `ReaderSpreadSmokeTest` 2 例在内存受限串行全量跑时
   偶发 `ComposeTimeoutException`（登记在案，非回归，单跑即过）；与本文件指标无关，
   列此以免误判。

## 4. 维护约定

- 改动 `reader/txt/**` 或 `data/repository/BookImporter.kt` 后，**必须**重跑本文
  §1.1 的复现命令并把新数值写回表格（只改数值，不删列）；
- 新增真机实测值：填 DEVICE_CHECKLIST §7.3，再把结论回写 §1.2 的「状态」列
  （`待真机补测` → `已测：<设备> <数值>`）；
- 本文件不设性能门禁（JVM 耗时/内存噪声大，设阈值只会制造假红）；
  唯一的自动化门禁是 `LargeBookImportTest` 的正确性断言与 10 分钟「防挂死」上限。
