# 贡献指南

感谢你愿意参与纸阅 PaperRead！

## 环境准备

1. JDK 17、Android SDK 35
2. Fork 并克隆仓库，`echo "sdk.dir=/path/to/Android/sdk" > local.properties`
3. `./gradlew assembleDebug` 确认能构建

> 国内网络环境如遇依赖下载缓慢，可在 `~/.gradle/init.gradle` 中配置镜像（阿里云等）加速，不影响仓库文件。

## 开发流程

1. 从 `main` 创建分支：`feat/xxx` 或 `fix/xxx`
2. 按 `docs/TASKS.md` 的工单执行，小步提交
3. **本地全绿再推送**：
   ```bash
   ./gradlew testClientDebugUnitTest testDevDebugUnitTest lint assembleDebug
   ```
4. 提交 PR，说明改动动机与影响面

## 提交规范

Conventional Commits，中文描述，例如：

```
feat(reader): 实现阅读器底部设置面板
fix(txt): 修复 GB18030 编码误判为 UTF-8 的问题
test(epub): 补充无 NCX 目录的解析用例
```

## 代码约定

- Kotlin，`kotlin.code.style=official`
- `reader/` 引擎保持零 Android 依赖（纯 Kotlin，可 JVM 单测）
- UI 层不直接访问 Room / 文件系统，一律经 Repository
- 新增第三方依赖需在 PR 中说明理由并在 README 依赖清单登记
- Manifest 永不添加 INTERNET 权限（v1 隐私承诺）

## 人工审查重点

数据模型、IO 边界、权限、并发、架构分层的改动需要重点审查，PR 中请主动说明。
