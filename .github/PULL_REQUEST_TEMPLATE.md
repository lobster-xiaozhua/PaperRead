## 改动说明

<!-- 动机、影响面、关联工单（docs/TASKS.md 编号） -->

## 改动类型

- [ ] feat 新功能
- [ ] fix 缺陷修复
- [ ] refactor 重构
- [ ] test 测试
- [ ] docs 文档
- [ ] ci / chore 工程

## 本地门禁（合并前需全绿）

- [ ] `./gradlew testDebugUnitTest lint assembleDebug` 全绿
- [ ] `./gradlew jacocoTestCoverageVerification` 通过（行覆盖率不低于门禁阈值）

## 人工把关项（命中请在描述中重点说明，见 AGENTS.md）

- [ ] 数据模型（Room Entity / DAO / 迁移）
- [ ] IO 边界（文件读写 / SAF / 编码探测）
- [ ] 权限与 Manifest
- [ ] 并发（协程作用域 / 调度器）
- [ ] 架构分层调整

## 其他自查

- [ ] 未新增第三方依赖（或已在 README「依赖清单」登记并说明理由）
- [ ] 已在 `docs/TASKS.md` 记录本次工单
- [ ] 涉及发布内容时已同步 `CHANGELOG.md`
