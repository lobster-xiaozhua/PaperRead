# 签名与发布安全（SIGNING）

## 1. 密钥就是应用身份
- Android 要求**同一签名密钥**才能覆盖安装旧版本。密钥一旦丢失，**该包名（io.github.lunalight.paperread）下的所有已装用户将无法收到任何更新**，且无法找回。
- 后果等级：最高。把密钥备份当作发布前必做项，优先级高于写代码。

## 2. 生成密钥
./scripts/generate-keystore.sh ~/.paperread/paperread-release.jks
生成 4096-bit RSA / PKCS12 / alias=paperread / 有效期 10000 天。

## 3. 密钥备份铁律（不可妥协）
- 至少 3 份离线副本（加密 U 盘、另一物理介质、可信云盘加密备份各一）。
- 绝不入库（.gitignore 已屏蔽 *.jks/*.keystore/keystore.properties）、绝不进 CI 日志、不贴 issue/聊天。
- 条件允许优先用离线/硬件密钥（YubiKey 等）托管，降低长期泄露面。
- 记录密钥库路径、alias、有效期截止日；设日历提醒在到期前轮换（见 §6）。

## 4. 本地签名发布
在 local.properties（或 ~/.gradle/gradle.properties）写入四个属性（见仓库根 keystore.properties.template），然后：
./gradlew assembleClientRelease
产出已签名 APK：app/build/outputs/apk/client/release/app-client-release.apk

## 5. CI / GitHub Actions 发布签名
- CI 的 assemble 作业故意不签名（只验证打包/R8），不持有私钥。
- 发布流程（release workflow）通过 Actions Secrets 注入四个属性再 assembleClientRelease；建议额外设 RELEASE_ENFORCE_SIGNING=true，缺签名直接失败。
- 产物经 apksigner verify 确认签名后再上传 GitHub Release。

## 6. 签名方案与密钥轮转
- 已显式开启 v1 + v2 + v3 签名方案。v3 支持密钥轮转（新密钥签名、旧密钥在 v3 块背书），未来密钥泄露可平滑轮换而不丢更新能力。

## 7. F-Droid 渠道（其自签，我们不需要给密钥）
- F-Droid 从源码标签自行构建并自签，安装包由 F-Droid 密钥签名，与我们的 release 密钥无关。
- 只提供给 F-Droid 可重现构建的源码 + fastlane 元数据，不交密钥给第三方。
- 构建须可重现：依赖锁定、不依赖本机临时文件。

## 8. 安装包冲突处理
- FileProvider 权限冲突（INSTALL_FAILED_CONFLICTING_PROVIDER）：已根除。清单 authorities="${applicationId}.fileprovider" 用 applicationId 参数化，client 与 dev 不撞。
- debug→release 升级冲突（INSTALL_FAILED_UPDATE_INCOMPATIBLE）：同包名不同签名会拒装。正式版以 release 密钥为唯一身份；dev 版用 .dev 后缀与正式版同机并存。终端用户从旧 debug 版升级到 release 版须先卸载旧 debug 版（数据用应用内备份导出再恢复）。发布说明升级注意已写明。
- 双重备份冲突：android:allowBackup 已置 false，关闭 Android 云备份/D2D，避免与应用自有备份（US-20，支持加密）两通道并存导致数据不一致 / adb backup 泄露。应用内备份是数据迁移唯一真相源。

## 9. versionCode 纪律
- versionCode 只能递增、不可回退（回退导致安装被拒）。每次发版 +1；app/build.gradle.kts defaultConfig 与 docs/release-notes-template.md 均已标注。
