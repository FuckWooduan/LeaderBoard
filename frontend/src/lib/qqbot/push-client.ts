/**
 * NapCat OneBot v11 —— 「只推送」精简客户端（无 WebSocket、无消息接收、无 @ 聊天）。
 *
 * 只用 NapCat 的 HTTP 接口（默认 http://127.0.0.1:3010）主动发消息：
 *   - 活动推送到机器人所在<b>全部群</b>（合并转发 / 单条）；
 *   - 同时推给机器人<b>全部好友</b>（私聊，合并转发 / 单条）。
 * 不连 NapCat 的正向 WS、不注册任何消息处理 → 机器人结构上不具备「被 @ 回复」能力。
 *
 * 图片：活动长图相对路径(/api/public/activity/{id}.png)走内网 API_BASE 读成 base64，不经公网/CDN。
 */

const HTTP_URL = process.env.NAPCAT_HTTP || "http://127.0.0.1:3010";
const TOKEN = process.env.NAPCAT_TOKEN || ""; // 与 NapCat OneBot HTTP 配置一致；本机回环可留空
const API_BASE = process.env.API_BASE || "http://127.0.0.1:8080";

export type PushItem = { title: string; imageUrl?: string };

async function napcatHttp(action: string, params: Record<string, unknown>): Promise<any> {
  const r = await fetch(`${HTTP_URL}/${action}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
    },
    body: JSON.stringify(params),
    signal: AbortSignal.timeout(30000),
  });
  if (!r.ok) throw new Error(`NapCat ${action} HTTP ${r.status}`);
  return r.json();
}

/**
 * 把图片地址转成 OneBot image 段：交给 NapCat 直接按 URL 拉取。相对路径补上内网 API_BASE
 * （http://127.0.0.1:8080，host 网络下 NapCat 可直连，不经公网/CDN）。
 * 不再走 base64——NapCat 4.x 对大图 base64 处理不稳（实测会丢图只发文字），URL 直发可靠。
 */
function imageSegment(src: string): any {
  const url = src.startsWith("/") ? `${API_BASE}${src}` : src;
  return { type: "image", data: { file: url } };
}

function buildSegments(text: string, imageUrl?: string): any[] {
  const segs: any[] = [];
  if (text) segs.push({ type: "text", data: { text } });
  if (imageUrl) segs.push(imageSegment(imageUrl));
  return segs.length ? segs : [{ type: "text", data: { text: "（无内容）" } }];
}

/** 合并转发节点：每个活动一文一图一条独立消息（节点）。 */
function buildForwardNodes(uin: string, items: PushItem[]): any[] {
  const nodes: any[] = [];
  for (const it of items) {
    const content: any[] = [{ type: "text", data: { text: `🎮 ${it.title}` } }];
    if (it.imageUrl) content.push(imageSegment(it.imageUrl));
    nodes.push({ type: "node", data: { user_id: uin, nickname: "StrikeGod 活动通知", content } });
  }
  return nodes;
}

let cachedUin: string | null = null;
/** 机器人自身 QQ 号（合并转发节点的发言人 uin）；取不到回退 10000，不阻塞推送。 */
async function loginUin(): Promise<string> {
  if (cachedUin) return cachedUin;
  try {
    const d = await napcatHttp("get_login_info", {});
    const id = Number(d?.data?.user_id);
    cachedUin = Number.isFinite(id) && id > 0 ? String(id) : "10000";
  } catch {
    cachedUin = "10000";
  }
  return cachedUin;
}

/** 主动发群消息（单个活动）。 */
export async function sendGroup(groupId: number, text: string, imageUrl?: string): Promise<void> {
  await napcatHttp("send_group_msg", { group_id: groupId, message: await buildSegments(text, imageUrl) });
}

/** 群合并转发（多个活动打包成一张转发卡片）。 */
export async function sendGroupForward(groupId: number, items: PushItem[]): Promise<void> {
  const nodes = await buildForwardNodes(await loginUin(), items);
  if (!nodes.length) return;
  await napcatHttp("send_group_forward_msg", { group_id: groupId, messages: nodes });
}

/** 主动发私聊（单个活动）。 */
export async function sendPrivate(userId: number, text: string, imageUrl?: string): Promise<void> {
  await napcatHttp("send_private_msg", { user_id: userId, message: await buildSegments(text, imageUrl) });
}

/** 私聊合并转发（多个活动）。 */
export async function sendPrivateForward(userId: number, items: PushItem[]): Promise<void> {
  const nodes = await buildForwardNodes(await loginUin(), items);
  if (!nodes.length) return;
  await napcatHttp("send_private_forward_msg", { user_id: userId, messages: nodes });
}

/** 机器人所在群列表（活动主动推送遍历用）。 */
export async function listGroups(): Promise<number[]> {
  try {
    const d = await napcatHttp("get_group_list", {});
    const arr = Array.isArray(d?.data) ? d.data : [];
    return arr.map((g: any) => Number(g.group_id)).filter((n: number) => Number.isFinite(n) && n > 0);
  } catch {
    return [];
  }
}

/** 机器人好友列表（活动也私聊推送给好友）。 */
export async function listFriends(): Promise<number[]> {
  try {
    const d = await napcatHttp("get_friend_list", {});
    const arr = Array.isArray(d?.data) ? d.data : [];
    return arr.map((f: any) => Number(f.user_id)).filter((n: number) => Number.isFinite(n) && n > 0);
  } catch {
    return [];
  }
}

/** 机器人在线状态（QQ 是否已登录在线）。 */
export async function getOnline(): Promise<boolean> {
  try {
    const d = await napcatHttp("get_status", {});
    return Boolean(d?.data?.online);
  } catch {
    return false;
  }
}

/** 机器人登录账号信息（QQ 号 + 昵称）；未登录/取不到返回 null。 */
export async function getLoginInfo(): Promise<{ uin: number; nick: string } | null> {
  try {
    const d = await napcatHttp("get_login_info", {});
    const uin = Number(d?.data?.user_id);
    if (!Number.isFinite(uin) || uin <= 0) return null;
    return { uin, nick: String(d?.data?.nickname || "") };
  } catch {
    return null;
  }
}
