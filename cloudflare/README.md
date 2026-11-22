# PaperRead 同步后端（Cloudflare Workers + D1 + KV）

Free 层、无 R2。账号系统（邮箱+密码）走 D1，零知识加密快照走 KV。服务端不持有书籍正文，也不持有同步明文。

## 架构

```
App（本地 AES-256-GCM 加密快照）
   │  HTTPS + Bearer JWT + X-Device-Id
   ▼
Worker paperread-sync
   ├─ /api/auth/*   注册/登录/刷新/登出/me  → D1(users, refresh_tokens)
   └─ /api/sync/*   快照 存/取/列设备/删     → KV(SYNC_KV，仅密文)
```

- **零知识**：同步快照由客户端用「账户密码」派生的密钥加密，服务端只存密文；账户密码不离开设备。
- **账号**：密码 Web Crypto PBKDF2-HMAC-SHA256（20 万迭代）；会话为 HMAC-SHA256 签名 JWT（24h），刷新令牌存 D1（30d、可吊销）。
- **范围**：仅同步应用数据库行（进度/书签/笔记/设置/字体元数据），**不同步书籍正文文件**。

## 部署（首次）

```bash
cd cloudflare
npm install
wrangler login                       # 浏览器授权 Cloudflare 账号
wrangler d1 create paperread         # 记下返回的 database_id，填入 wrangler.jsonc
wrangler kv namespace create SYNC_KV # 记下返回的 id，填入 wrangler.jsonc
wrangler secret put SYNC_JWT_SECRET  # 输入一段随机长字符串（服务端签名密钥）
wrangler d1 migrations apply paperread --remote
wrangler deploy
```

## 本地联调

```bash
wrangler d1 migrations apply paperread --local
wrangler dev      # 默认 http://127.0.0.1:8787
```

## 接口

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/auth/register` | `{email,password}` → 用户+令牌 | 否 |
| POST | `/api/auth/login` | `{email,password}` → 令牌 | 否 |
| POST | `/api/auth/refresh` | `{refresh_token}` → 新令牌（轮换旧令牌） | 否 |
| POST | `/api/auth/logout` | `{refresh_token}` → 吊销 | 否 |
| GET | `/api/auth/me` | 当前用户 | 是 |
| PUT | `/api/sync/snapshot` | `{ciphertext,lastModified}`，头带 `X-Device-Id` | 是 |
| GET | `/api/sync/snapshot` | 取本设备快照 | 是 |
| GET | `/api/sync/devices` | 列出该用户全部设备及其 `lastModified` | 是 |
| DELETE | `/api/sync/snapshot` | 删本设备快照 | 是 |

## Android 客户端接入规范（下一步待实现）

1. **新增模块** `:core:sync`（纯 JVM/网络），定义 `SyncClient`（OkHttp）与 `SyncRepository`。
2. **令牌存储**：access/refresh token 存 **Android Keystore**（信封加密），不落 DataStore 明文。
3. **快照加密**：用「账户密码」经 PBKDF2 派生密钥（**MVP 使用单一账户密码同时用于登录与备份加密**，不另设同步口令；遗忘密码=无法解密云端快照），AES-256-GCM 加密整份「仅状态」DB 导出 JSON（不含书籍正文），PUT `/api/sync/snapshot`。
4. **合并**：GET `/api/sync/devices` 选 `lastModified` 最新者，拉取后按实体 `lastModified` 三路合并（与现有 `BackupCrypto` 思路一致）。
5. **零知识**：同步口令不离开设备；服务端只看到密文。
6. **默认关闭**：设置页开关；无后端时本地优先行为不变，F-Droid 构建（若保留）不受影响。

## 成本（Free 层，约 $0/月）

Workers 10 万请求/天、D1 5GB/500 万行读、KV 1GB/10 万读·1 千写。个人同步用量远低于上限。KV 为最终一致（边缘秒级），换机同步无碍。

## 已知限制

- 免费层无 Cloudflare China Network 节点（需 Enterprise+ICP）；大陆用户访问全球网络可能慢/不稳。后端本就可选，慢不影响本地使用。
- KV `list` 为最终一致且分页，设备列表可能有秒级延迟。
- 未做邮箱验证（私人开发可跳过）；如需可后续接邮件服务。
