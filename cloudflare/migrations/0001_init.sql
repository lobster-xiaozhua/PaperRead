-- PaperRead 账号与刷新令牌表（Free 层 D1）
CREATE TABLE IF NOT EXISTS users (
  id         TEXT PRIMARY KEY,
  email      TEXT UNIQUE NOT NULL,
  pw_hash    TEXT NOT NULL,
  pw_salt    TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  id         TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL,
  user_id    TEXT NOT NULL,
  device_id  TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  revoked    INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_rt_user ON refresh_tokens(user_id);
