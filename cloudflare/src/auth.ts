// 账号系统：注册 / 登录 / 刷新 / 登出 / 当前用户
// 密码用 Web Crypto PBKDF2-HMAC-SHA256（20 万迭代）哈希；会话用 HMAC-SHA256 签名的 JWT。
import type { Env } from "./index";
import { json, b64url, b64urlDecode, sha256, timingSafeEqual } from "./util";

const enc = new TextEncoder();

const PBKDF2_ITER = 200_000;
const ACCESS_TTL = 60 * 60 * 24; // 24h
const REFRESH_TTL = 60 * 60 * 24 * 30; // 30d

async function hashPassword(password: string, salt: Uint8Array): Promise<string> {
  const keyMat = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt, iterations: PBKDF2_ITER, hash: "SHA-256" },
    keyMat,
    256,
  );
  return b64url(new Uint8Array(bits));
}

async function signJwt(payload: Record<string, unknown>, secret: string): Promise<string> {
  const header = b64url(enc.encode(JSON.stringify({ alg: "HS256", typ: "JWT" })));
  const body = b64url(enc.encode(JSON.stringify(payload)));
  const data = `${header}.${body}`;
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(data));
  return `${data}.${b64url(new Uint8Array(sig))}`;
}

async function verifyJwt(token: string, secret: string): Promise<Record<string, unknown> | null> {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const data = `${parts[0]}.${parts[1]}`;
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const ok = await crypto.subtle.verify("HMAC", key, b64urlDecode(parts[2]), enc.encode(data));
  if (!ok) return null;
  const payload = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[1]))) as Record<string, unknown>;
  if (typeof payload.exp === "number" && payload.exp * 1000 < Date.now()) return null;
  return payload;
}

function bearer(req: Request): string | null {
  const h = req.headers.get("Authorization");
  if (!h || !h.startsWith("Bearer ")) return null;
  return h.slice(7);
}

export async function verifyAuth(req: Request, env: Env): Promise<{ uid: string } | null> {
  const token = bearer(req);
  if (!token) return null;
  const payload = await verifyJwt(token, env.SYNC_JWT_SECRET);
  if (!payload || typeof payload.sub !== "string") return null;
  return { uid: payload.sub };
}

async function issueTokens(env: Env, uid: string, deviceId: string) {
  const now = Math.floor(Date.now() / 1000);
  const access = await signJwt({ sub: uid, iat: now, exp: now + ACCESS_TTL }, env.SYNC_JWT_SECRET);
  const rtId = crypto.randomUUID();
  const rtPlain = crypto.randomUUID();
  await env.DB.prepare(
    "INSERT INTO refresh_tokens (id, token_hash, user_id, device_id, expires_at, created_at, revoked) VALUES (?, ?, ?, ?, ?, ?, 0)",
  )
    .bind(rtId, await sha256(rtPlain), uid, deviceId, Date.now() + REFRESH_TTL * 1000, Date.now())
    .run();
  return { access_token: access, refresh_token: `${rtId}.${rtPlain}`, expires_in: ACCESS_TTL };
}

export async function handleAuth(req: Request, env: Env, url: URL): Promise<Response> {
  const sub = url.pathname.replace("/api/auth/", "");

  if (req.method === "POST" && sub === "register") {
    const { email, password } = (await req.json()) as { email?: string; password?: string };
    if (!email || !password || password.length < 8) return json({ error: "invalid_input" }, 400);
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const hash = await hashPassword(password, salt);
    const uid = crypto.randomUUID();
    try {
      await env.DB.prepare("INSERT INTO users (id, email, pw_hash, pw_salt, created_at) VALUES (?, ?, ?, ?, ?)")
        .bind(uid, email.toLowerCase(), hash, b64url(salt), Date.now())
        .run();
    } catch (e) {
      if (String(e).includes("UNIQUE")) return json({ error: "email_taken" }, 409);
      throw e;
    }
    const deviceId = req.headers.get("X-Device-Id") || crypto.randomUUID();
    const tokens = await issueTokens(env, uid, deviceId);
    return json({ user: { id: uid, email: email.toLowerCase() }, ...tokens }, 201);
  }

  if (req.method === "POST" && sub === "login") {
    const { email, password } = (await req.json()) as { email?: string; password?: string };
    if (!email || !password) return json({ error: "invalid_input" }, 400);
    const row = await env.DB.prepare("SELECT id, pw_hash, pw_salt FROM users WHERE email = ?")
      .bind(email.toLowerCase())
      .first<{ id: string; pw_hash: string; pw_salt: string }>();
    if (!row) return json({ error: "invalid_credentials" }, 401);
    const hash = await hashPassword(password, b64urlDecode(row.pw_salt));
    if (!timingSafeEqual(hash, row.pw_hash)) return json({ error: "invalid_credentials" }, 401);
    const deviceId = req.headers.get("X-Device-Id") || crypto.randomUUID();
    const tokens = await issueTokens(env, row.id, deviceId);
    return json({ user: { id: row.id, email: email.toLowerCase() }, ...tokens });
  }

  if (req.method === "POST" && sub === "refresh") {
    const { refresh_token } = (await req.json()) as { refresh_token?: string };
    if (!refresh_token || !refresh_token.includes(".")) return json({ error: "invalid_token" }, 400);
    const [rtId, rtPlain] = refresh_token.split(".");
    const row = await env.DB.prepare("SELECT user_id, expires_at, revoked, token_hash FROM refresh_tokens WHERE id = ?")
      .bind(rtId)
      .first<{ user_id: string; expires_at: number; revoked: number; token_hash: string }>();
    if (!row || row.revoked === 1 || row.expires_at < Date.now()) return json({ error: "invalid_token" }, 401);
    if (!timingSafeEqual(row.token_hash, await sha256(rtPlain))) return json({ error: "invalid_token" }, 401);
    await env.DB.prepare("UPDATE refresh_tokens SET revoked = 1 WHERE id = ?").bind(rtId).run();
    const deviceId = req.headers.get("X-Device-Id") || crypto.randomUUID();
    const tokens = await issueTokens(env, row.user_id, deviceId);
    return json(tokens);
  }

  if (req.method === "POST" && sub === "logout") {
    const { refresh_token } = (await req.json()) as { refresh_token?: string };
    if (refresh_token && refresh_token.includes(".")) {
      const [rtId] = refresh_token.split(".");
      await env.DB.prepare("UPDATE refresh_tokens SET revoked = 1 WHERE id = ?").bind(rtId).run();
    }
    return json({ ok: true });
  }

  if (req.method === "GET" && sub === "me") {
    const auth = await verifyAuth(req, env);
    if (!auth) return json({ error: "unauthorized" }, 401);
    const row = await env.DB.prepare("SELECT id, email FROM users WHERE id = ?").bind(auth.uid).first();
    return json({ user: row });
  }

  return json({ error: "method_not_allowed" }, 405);
}
