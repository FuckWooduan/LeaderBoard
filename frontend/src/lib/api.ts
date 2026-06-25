/** API 客户端：浏览器侧同源相对路径（nginx 分流 /api → Spring）；服务端（SSR 首屏）直连 Spring。 */

/** 仅服务端（Server Component / SSR）使用：直连 Spring 取首屏数据，永不缓存。 */
export async function serverApi<T = any>(path: string): Promise<T | null> {
  const base = process.env.API_BASE || "http://127.0.0.1:8080";
  try {
    const r = await fetch(base + path, {
      headers: { Accept: "application/json" },
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    if (!r.ok) return null;
    return (await r.json()) as T;
  } catch {
    return null; // 后端未就绪 → 页面退化为客户端拉取，不阻塞 SSR
  }
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function parse(r: Response) {
  if (!r.ok) {
    const text = await r.text().catch(() => "");
    let msg = text || r.statusText;
    try {
      const j = JSON.parse(text);
      msg = j.message || j.error || msg;
    } catch {
      /* 非 JSON 错误体，用原文 */
    }
    throw new ApiError(r.status, msg);
  }
  return r.json();
}

function userToken(): string | null {
  if (typeof localStorage === "undefined") return null;
  return localStorage.getItem("user_token");
}

function adminToken(): string | null {
  if (typeof localStorage === "undefined") return null;
  return localStorage.getItem("admin_token");
}

function headers(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { Accept: "application/json", ...extra };
  const ut = userToken();
  if (ut) h["X-User-Token"] = ut;
  return h;
}

export async function api<T = any>(path: string): Promise<T> {
  return parse(await fetch(path, { headers: headers() }));
}

export async function post<T = any>(path: string, body?: unknown): Promise<T> {
  return parse(
    await fetch(path, {
      method: "POST",
      headers: headers({ "Content-Type": "application/json" }),
      body: JSON.stringify(body ?? {}),
    })
  );
}

/** 后台请求头：X-Admin-Token（站长 TOTP）与 X-User-Token（管理员账号）双轨。 */
export function adminHeaders(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { Accept: "application/json", ...extra };
  const at = adminToken();
  if (at) h["X-Admin-Token"] = at;
  const ut = userToken();
  if (ut) h["X-User-Token"] = ut;
  return h;
}

/**
 * 后台 API：401 时清掉失效的站长 token 并跳后台登录页。
 *
 * <p>关键：当前已在 /admin（登录页本身）时<b>绝不跳转</b>——否则登录页的「探测登录态」请求
 * 收到 401 → 跳 /admin → 重载 → 再探测 → 再 401，会陷入无限刷新循环。
 */
export async function adminApi<T = any>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, {
    ...init,
    headers: adminHeaders(init?.body ? { "Content-Type": "application/json" } : undefined),
  });
  if (r.status === 401 && typeof location !== "undefined") {
    // 站长 token 已失效（若 user_token 是有效管理员则不会 401），清掉避免反复探测；
    // user_token 保留——可能是普通用户的有效前台登录态。
    try {
      localStorage.removeItem("admin_token");
    } catch {}
    if (location.pathname !== "/admin" && location.pathname !== "/admin/") {
      location.href = "/admin";
    }
    throw new ApiError(401, "未登录");
  }
  return parse(r);
}

export function adminPost<T = any>(path: string, body?: unknown): Promise<T> {
  return adminApi<T>(path, { method: "POST", body: JSON.stringify(body ?? {}) });
}
