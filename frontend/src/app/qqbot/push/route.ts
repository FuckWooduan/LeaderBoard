/**
 * 活动主动推送入口（仅限本机 Spring 调用）：检测到新活动后 POST 此端点，经 NapCat OneBot 把活动
 * （标题文本 + 解析长图）推到机器人所在的<b>全部 QQ 群</b>，同时私聊推给机器人<b>全部好友</b>。
 *
 * 鉴权：X-Push-Token 必须等于 QQBOT_PUSH_TOKEN（与 Spring 端 rankharvester.qqbot.push-token 同值）。
 * NapCat 个人号无官方「每群每月4条」限制；逐群/逐好友 try/catch，单点失败不影响其余。
 */

import { NextResponse } from "next/server";
import {
  listFriends,
  listGroups,
  sendGroup,
  sendGroupForward,
  sendPrivate,
  sendPrivateForward,
  type PushItem,
} from "@/lib/qqbot/push-client";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const PUSH_TOKEN = process.env.QQBOT_PUSH_TOKEN || "";

export async function POST(req: Request): Promise<NextResponse> {
  if (!PUSH_TOKEN || req.headers.get("x-push-token") !== PUSH_TOKEN) {
    return NextResponse.json({ ok: false, message: "unauthorized" }, { status: 401 });
  }
  const body = await req.json().catch(() => ({}));
  const list: any[] = Array.isArray(body.activities) ? body.activities : [];
  const png = (id: number) => (id > 0 ? `/api/public/activity/${id}.png` : undefined);
  const clean = (s: unknown) => String(s || "").replace(/<[^>]+>/g, "").trim();

  // 多个活动 → 合并转发（每个活动一文一图一条独立消息）；单个/legacy → 单条消息。
  const forwardItems: PushItem[] | null =
    list.length >= 2
      ? list.map((a) => ({ title: clean(a?.title) || "新活动", imageUrl: png(Number(a?.activityId || 0)) }))
      : null;

  let text = "";
  let imageUrl: string | undefined;
  if (!forwardItems) {
    if (list.length === 1) {
      const a = list[0];
      text = `🎮 新活动上线：${clean(a?.title)}`;
      imageUrl = png(Number(a?.activityId || 0));
    } else {
      const activityId = Number(body.activityId || 0);
      const title = clean(body.title);
      text =
        String(body.text || "") ||
        `🎮 新活动上线：${title}${body.startTime ? `\n⏰ ${body.startTime} ~ ${body.endTime || ""}` : ""}`;
      imageUrl = png(activityId);
    }
  }

  // 可选 body.groups=[群号...]：只发这些群（测试用，避免一次轰所有群）；不传则发机器人所在全部群。
  const onlyGroups: number[] = Array.isArray(body.groups)
    ? body.groups.map((g: any) => Number(g)).filter((n: number) => Number.isFinite(n) && n > 0)
    : [];
  const groups = onlyGroups.length ? onlyGroups : await listGroups();
  const friends = onlyGroups.length ? [] : await listFriends();

  if (!groups.length && !friends.length) {
    return NextResponse.json({ ok: true, sent: 0, message: "机器人未登录或无群/好友" });
  }

  let sent = 0;
  const errors: string[] = [];
  for (const g of groups) {
    try {
      if (forwardItems) await sendGroupForward(g, forwardItems);
      else await sendGroup(g, text, imageUrl);
      sent++;
    } catch (e: any) {
      errors.push(`group ${g}: ${String(e?.message || e).slice(0, 80)}`);
    }
  }
  for (const u of friends) {
    try {
      if (forwardItems) await sendPrivateForward(u, forwardItems);
      else await sendPrivate(u, text, imageUrl);
      sent++;
    } catch (e: any) {
      errors.push(`friend ${u}: ${String(e?.message || e).slice(0, 80)}`);
    }
  }

  return NextResponse.json({
    ok: true,
    sent,
    groups: groups.length,
    friends: friends.length,
    errors: errors.slice(0, 20),
  });
}
