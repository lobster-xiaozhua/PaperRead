# 安全政策

## 支持的版本

仅 `main` 主线与最近一个 Release 会收到安全修复。请尽量使用最新版本。

## 攻击面说明

纸阅 PaperRead **完全离线、不申请 INTERNET 权限**，没有任何网络行为。攻击面主要集中在**本地文件解析**（TXT / EPUB / 编码识别 / 章节切分）与**数据持久化**（Room / DataStore / 本地文件）。权限侧仅涉及 SAF 选文件，无敏感权限。

## 报告漏洞

如果你发现安全漏洞，请**不要**提交公开 Issue 或 Pull Request，而是通过以下方式**私下**报告：

1. 优先使用 GitHub 的 [Private Vulnerability Reporting](https://github.com/lobster-xiaozhua/PaperRead/security/advisories/new)（建议）
2. 或联系仓库维护者（见其 GitHub 公开资料中的联系方式）

报告请尽量包含：受影响版本、复现步骤 / 样本、潜在影响。我们会在 **72 小时**内回应，并在修复发布后（经你同意）致谢。

## 范围外

- 需要设备已 root / 已解锁 bootloader 的本地提权
- 依赖物理接触设备的攻击（如恶意更换存储介质）
- 上游依赖（AndroidX / Kotlin / Room / Robolectric 等）的已知问题——请直接上报给对应上游项目
