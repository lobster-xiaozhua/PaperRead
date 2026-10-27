# 网络行为审计（NETWORK_AUDIT）

> 本文档公开纸阅的全部网络行为口径与验证机制，对应需求 R-02（G2）、
> US-20 / AC-20.5（CI 网络断言 + 审计文档公开）。
> 每次网络行为相关变更（新增 URL、新增 socket、新增依赖仓库）都必须
> 同步更新本文档，并由 CI 的 `network-audit` 作业兜底。

## 结论

**纸阅不访问公网。** 应用运行时的全部网络行为收敛在「同一局域网内的
传书 / 换机迁移 / 进度同步」一个场景，除此之外：

- 书源解析、章节切分、全文检索在本机完成；
- 朗读合成由系统 TTS 引擎离线完成（引擎自身的语音数据下载属系统
  组件行为，纸阅不发起、不感知）；
- 不内置任何远程接口调用，不加载远程资源，不上传任何数据；
- 不集成任何第三方 SDK 与统计库。

## 网络行为清单（全部）

| # | 行为 | 实现位置 | 范围 | 触发方式 |
|---|------|---------|------|---------|
| 1 | 局域网设备发现 | `transfer/LanDiscovery.kt`、`transfer/LanAddresses.kt` | 同一网段 UDP 广播 / 多播 | 用户进入传书页主动触发 |
| 2 | 局域网传书 / 迁移 / 同步 | `transfer/TransferClient.kt`（客户端）、`transfer/TransferHttpServer.kt` + `transfer/TransferServer.kt`（服务端）、`transfer/LanTransferManager.kt`（门面） | HTTP over 局域网（`http://$host:$port`，host 来自 #1 的发现结果） | 用户在传书页确认后发起 |
| 3 | 外链拒绝守卫 | `reader/epub/EpubParser.kt` | 无网络行为——解析到 `http(s)://` 外链资源即整体拒绝导入 | 导入 EPUB 时 |
| 4 | 项目主页 / 问题反馈外链 | `ui/about/AboutScreen.kt`（AboutLinks） | 交系统浏览器（ACTION_VIEW）打开，离开应用后由浏览器承担 | 用户在关于页主动点击 |

清单之外不存在第五种网络行为。CI 断言脚本
`scripts/check-network-behavior.sh` 把该清单固化为门禁（见下文）。

## 权限与网络的关系

Manifest 声明 `android.permission.INTERNET`，理由是 Android 的技术
事实：**任何** socket（包括仅连接局域网 IP）都必须声明该权限，没有
「仅局域网」的细分权限。声明 INTERNET 不等于访问公网——真实用途
以上方清单为准，并由 CI 静态断言（URL 白名单 + 网络 API 收敛）
可验证地兜底。除此之外未声明任何网络相关权限（如
ACCESS_NETWORK_STATE 亦未使用）。

运行时权限 `POST_NOTIFICATIONS` 与网络无关，仅用于朗读控制通知，
其申请时机（首次朗读前场景化说明、拒绝后朗读仍可播放）见隐私说明
一页与 `docs/SPEC.md` 的 US-20。

## 构建期 URL 白名单（非运行时 endpoint）

构建脚本中的 URL 服务于编译期 / 测试期的依赖解析，不随 APK 分发、
不是应用运行时 endpoint，列入白名单并注明理由：

| 位置 | URL | 用途 |
|------|-----|------|
| `settings.gradle.kts` | `google()` / `mavenCentral()` / `gradlePluginPortal()`（工厂方法，无字面量） | Gradle 插件与依赖解析 |
| `app/build.gradle.kts` | `-Probolectric.dependency.repo.url=https://maven.aliyun.com/repository/public`（注释中的可选覆盖参数） | 国内环境加速 Robolectric `android-all` jar 下载，仅影响测试运行期 |

仓库依赖全部来自官方仓库；无私有 Maven 仓库、无 URL 形式的
运行时配置下发。

## 验证机制（如何证明「没偷偷上网」）

1. **CI `network-audit` 作业**：执行 `scripts/check-network-behavior.sh`，
   五项断言任一失败即门禁红——
   - 三方网络 / 统计 SDK 禁入（构建配置坐标 + 源码 import 双通道扫描）；
   - `app/src/main/java` 的 URL 字面量必须落在白名单文件内，且形状
     符合该文件口径（局域网仅 `$host:$port` 插值 / EPUB 外链仅
     `startsWith` 拒绝守卫 / 项目外链仅两条已登记 GitHub 地址）；
   - socket 级网络 API（Socket / ServerSocket / DatagramSocket /
     HttpURLConnection / openConnection / InetAddress）仅允许出现在
     `transfer/**`；
   - 构建配置 URL 仅允许命中依赖仓库白名单；
   - Manifest 中 INTERNET 权限存在且理由充分。
2. **隐私页一致性单测**（AC-20.4）：`AboutScreenTest` 把隐私说明一页
   展示的权限清单与合并 Manifest 的 requestedPermissions 逐一比对，
   页面陈述与实际一致；新增权限必须先进隐私评审再进清单。
3. **依赖坐标白名单单测**（`BuildDependencyAllowlistTest`）：脚本第 1 项是
   **关键字黑名单**（okhttp / retrofit / firebase / umeng …），换个未登记的
   公网 SDK 就拦不住；该测试把黑名单补成**白名单**——`libs.versions.toml`
   与所有 `*.gradle.kts` 中的依赖坐标必须命中已评审的 group 前缀，
   未登记即失败（等价于强制过一次隐私评审），并禁止 `app/libs` / `jniLibs`
   下的本地二进制旁路坐标检查。
4. **本文档**：网络行为的唯一公开口径，与隐私说明一页
   （设置 → 关于 → 隐私说明）同源。

## 验证机制的能力边界（诚实口径）

上表四项是**静态**断言，能证明的范围必须说清楚，不做过度承诺：

| 手段 | 能证明 | 不能证明 |
|------|--------|---------|
| `scripts/check-network-behavior.sh` | ① 已知三方网络 / 统计 SDK 的关键字（坐标 + import 双通道）未出现；② `app/src/main/java` 中 **http(s):// 字面量**全部落在白名单文件内；③ socket 级 API 只出现在 `transfer/**`；④ 构建配置 URL 命中依赖仓库白名单；⑤ Manifest 的 INTERNET 声明与理由 | 未列入黑名单的第三方 SDK（由 `BuildDependencyAllowlistTest` 的坐标白名单承担）；**拼接式 / 运行时构造的 URL**（如 `"ht" + "tp://" + host`、读配置拼串、`String.format` 组装）；native / so 内部的网络调用；反射调用网络 API |
| `BuildDependencyAllowlistTest` | 依赖坐标来自已评审的 group 前缀，且无本地二进制旁路 | 已登记依赖**内部**新增的网络行为（依赖升级后的实现变化需人工评审 diff） |
| 隐私页一致性单测 | 隐私页陈述的权限清单 == 合并 Manifest 的实际权限 | 权限的实际使用方式（运行时行为仍需审计阅读） |

**明确的盲区**：静态扫描对「拼接式 URL」无能为力——这是文本匹配方法的固有限制，
不是脚本 bug。因此本项目的网络承诺最终仍依赖两条人工防线：（a）`transfer/**`
之外的 socket API 一律不允许出现（脚本第 3 项，收敛攻破成本）；（b）任何 PR 触及
`transfer/**` 或依赖坐标变更，必须同步更新本文档并由维护者审阅
（`.github/CODEOWNERS`）。

## 豁免清单及理由

以下位置出现的 `http(s)://` 不是网络行为，脚本显式排除：

- `app/src/test/**`：EPUB / XML 测试夹具中的命名空间标识符
  （w3.org / idpf.org / purl.org / daisy.org 等），是格式规范命名空间，
  不产生任何连接；
- `app/src/main/res/**`：Android XML 命名空间
  （schemas.android.com）与开源许可全文中的项目链接；
- `docs/` 与 `*.md`（README / SECURITY / CONTRIBUTING 等）：面向读者
  的文档链接，其中 SECURITY.md 为既有文档豁免。

## 新增网络行为的流程

任何新增 URL、socket 或第三方网络依赖，必须：

1. 先在本文档登记（行为清单 / 白名单 / 豁免三张表择一，注明理由）；
2. 通过隐私评审（R-02）；
3. 同步更新隐私说明一页（如影响用户可见承诺）；
4. 如涉及新依赖，在 `BuildDependencyAllowlistTest` 的分组前缀白名单里登记坐标；
5. CI 的 `network-audit` 作业（脚本）与 `unit` 作业（坐标白名单单测）放行。

跳过上述步骤中**可被静态检出**的部分（白名单外 URL 字面量、网络 API 越界、
未登记的依赖坐标），CI 会失败，防止「先斩后奏」式扩权；但拼接式 URL 与
已登记依赖内部的实现变化**不在**静态检出范围内（见上文「验证机制的能力边界」），
这两类只能靠 1–3 步的评审兜底。
