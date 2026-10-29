// PaperRead 同步后端入口
// 路由：/api/auth/*（注册/登录/刷新/登出/me）、/api/sync/*（需鉴权，零知识快照）
import { json, corsPreflight } from "./util";
import { handleAuth, verifyAuth } from "./auth";
import { handleSync } from "./sync";

export interface Env {
  DB: D1Database;
  SYNC_KV: KVNamespace;
  SYNC_JWT_SECRET: string;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method === "OPTIONS") return corsPreflight();
    const url = new URL(req.url);
    try {
      if (url.pathname.startsWith("/api/auth")) return await handleAuth(req, env, url);
      if (url.pathname.startsWith("/api/sync")) {
        const auth = await verifyAuth(req, env);
        if (!auth) return json({ error: "unauthorized" }, 401);
        return await handleSync(req, env, url, auth.uid);
      }
      return json({ error: "not_found" }, 404);
    } catch (e) {
      return json({ error: (e as Error).message ?? "internal_error" }, 500);
    }
  },
};
