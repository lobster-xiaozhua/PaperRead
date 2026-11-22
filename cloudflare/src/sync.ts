// 同步层：零知识加密快照存于 KV（服务端只看到密文）
// 密钥来自客户端「同步口令」，绝不离开设备；服务端仅按用户+设备存/取整份加密 JSON。
import type { Env } from "./index";
import { json } from "./util";

function deviceKey(uid: string, deviceId: string): string {
  return `sync:${uid}:${deviceId}`;
}

export async function handleSync(req: Request, env: Env, url: URL, uid: string): Promise<Response> {
  const deviceId = req.headers.get("X-Device-Id");
  if (!deviceId) return json({ error: "missing_device_id" }, 400);
  const sub = url.pathname.replace("/api/sync/", ""); // "" | "snapshot" | "devices"

  if ((req.method === "PUT") && (sub === "snapshot" || sub === "")) {
    const body = (await req.json()) as { ciphertext?: string; lastModified?: number };
    if (!body.ciphertext) return json({ error: "missing_ciphertext" }, 400);
    const lastModified = body.lastModified ?? Date.now();
    await env.SYNC_KV.put(
      deviceKey(uid, deviceId),
      JSON.stringify({ ciphertext: body.ciphertext, lastModified, updatedAt: Date.now() }),
    );
    return json({ ok: true, lastModified });
  }

  if (req.method === "GET" && (sub === "snapshot" || sub === "")) {
    const val = await env.SYNC_KV.get(deviceKey(uid, deviceId));
    return json({ snapshot: val ? JSON.parse(val) : null });
  }

  if (req.method === "GET" && sub === "devices") {
    const prefix = `sync:${uid}:`;
    const devices: Array<{ deviceId: string; lastModified: number; updatedAt: number }> = [];
    let cursor: string | undefined;
    do {
      const r = await env.SYNC_KV.list({ prefix, cursor });
      for (const k of r.keys) {
        const v = await env.SYNC_KV.get(k.name);
        if (v) {
          const o = JSON.parse(v);
          devices.push({ deviceId: k.name.slice(prefix.length), lastModified: o.lastModified, updatedAt: o.updatedAt });
        }
      }
      cursor = r.list_complete ? undefined : r.cursor;
    } while (cursor);
    return json({ devices });
  }

  if (req.method === "DELETE" && (sub === "snapshot" || sub === "")) {
    await env.SYNC_KV.delete(deviceKey(uid, deviceId));
    return json({ ok: true });
  }

  return json({ error: "method_not_allowed" }, 405);
}
