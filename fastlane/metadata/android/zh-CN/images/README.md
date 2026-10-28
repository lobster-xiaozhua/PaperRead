# fastlane 图像素材（待补）

F-Droid 商店页要求本目录提供图像素材。本仓库开发沙箱无法运行模拟器截图，
素材需在真机上采集后补齐。要求（以 F-Droid 官方规范为准）：

| 路径 | 要求 |
|---|---|
| `icon.png` | 512 × 512 PNG，应用图标（本仓库图标为矢量图，需导出位图） |
| `phoneScreenshots/` | 手机竖屏截图若干（建议 ≥ 4 张：书架 / 阅读器 / 传书 / 备份设置），PNG，最短边 ≥ 1080px 优先 |
| `featureGraphic.png` | 1024 × 500 横幅（可选但建议提供） |

采集指引：

1. 真机安装 client 正式版（`./gradlew assembleClientRelease`），按
   `docs/DEVICE_CHECKLIST.md` 走查时顺手截图；
2. 截图前切换到默认主题（纸白）并清空调试数据，保证素材一致性；
3. 截图不得包含真实个人书籍内容时需换成公开版权样章
   （可用 `scripts/gen-large-book-sample.sh` 生成的样书节选）。

本 README 在素材补齐后应替换为实际素材清单。
