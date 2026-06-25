/**
 * NapCat OneBot 事件回调（仅限本机：NapCat httpClient POST 到此）。
 *
 * 刻意只处理「主人私聊发 /status」一条命令 → 回复机器人运行状态；其余一切消息一律忽略
 * （不回任何内容）——所以机器人对外仍不具备聊天能力，只是给主人一个状态查询口子。
 *
 * 安全：本路由仅供本机 NapCat 调用；公网访问由 nginx `location ^~ /qqbot/ { return 404; }` 拦截。
 * 即便被本机其它进程伪造调用，最坏后果也只是给主人 QQ 发一条 /status 回执，无信息外泄。
 */

import { NextResponse } from "next/server";
import { getLoginInfo, getOnline, listFriends, listGroups, sendPrivate } from "@/lib/qqbot/push-client";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const OWNER_QQ = process.env.QQBOT_OWNER_QQ || "";

/** 北京时间格式化（容器时区可能为 UTC，固定按 Asia/Shanghai 显示）。 */
function fmtBeijing(ts: number): string {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
  }).format(new Date(ts));
}

/** 运行时长（天/时/分）。 */
function fmtDuration(ms: number): string {
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const parts: string[] = [];
  if (d) parts.push(`${d}天`);
  if (h) parts.push(`${h}小时`);
  parts.push(`${m}分`);
  return parts.join("");
}

/** 从 OneBot 消息体取纯文本（array / raw_message 两种格式都兜住）。 */
function extractText(ev: any): string {
  const segs = Array.isArray(ev?.message) ? ev.message : [];
  let text = "";
  for (const s of segs) if (s?.type === "text") text += s?.data?.text || "";
  if (!text && typeof ev?.raw_message === "string") text = ev.raw_message;
  return text.trim();
}

async function buildStatusReply(): Promise<string> {
  const startedAt = Date.now() - Math.floor(process.uptime() * 1000); // 推送服务(node)进程启动时刻
  const now = Date.now();
  const [online, login, groups, friends] = await Promise.all([
    getOnline(), getLoginInfo(), listGroups(), listFriends(),
  ]);
  const acct = login ? `${login.nick || "(无昵称)"}（${login.uin}）` : "未登录";
  return [
    "🤖 StrikeGod 活动推送机器人",
    `• 账号：${acct}`,
    `• 在线：${online ? "✅ 在线" : "❌ 离线"}`,
    `• 启动时间：${fmtBeijing(startedAt)}`,
    `• 运行时长：${fmtDuration(now - startedAt)}`,
    `• 推送目标：${groups.length} 个群 / ${friends.length} 个好友`,
    `• 当前时间：${fmtBeijing(now)}`,
  ].join("\n");
}

export async function POST(req: Request): Promise<NextResponse> {
  let ev: any;
  try {
    ev = await req.json();
  } catch {
    return NextResponse.json({ ok: true }); // 非 JSON：忽略
  }

  // 只认：主人 + 私聊 + 文本恰为 /status（其余一切消息一律不回）
  if (
    ev?.post_type === "message" &&
    ev?.message_type === "private" &&
    String(ev?.user_id) === OWNER_QQ
  ) {
    const text = extractText(ev);
    if (/^\/status$/i.test(text)) {
      try {
        await sendPrivate(Number(ev.user_id), await buildStatusReply());
      } catch (e: any) {
        // 取状态失败也给主人一个回执，避免“发了没反应”
        try {
          await sendPrivate(Number(ev.user_id), `状态查询失败：${String(e?.message || e).slice(0, 120)}`);
        } catch { /* ignore */ }
      }
    }
  }

  return NextResponse.json({ ok: true }); // OneBot 约定：快速 200，不在响应体里下发动作
}
