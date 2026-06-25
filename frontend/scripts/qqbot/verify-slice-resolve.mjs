#!/usr/bin/env node
/**
 * 分区榜智能解析单测（纯逻辑，无网络）。
 *
 * 用 2026-06-11 线上 /api/internal/slice/keys 抓到的真实榜目录做 fixture，
 * 断言 parseSliceQuery + pickSliceKey 能把「查仲夏之梦81区异能分」这类
 * 「榜名 + 维度词 + 分区号」正确拆分并定位到 rankKey。
 *
 * 运行：node scripts/qqbot/verify-slice-resolve.mjs
 */

import { parseSliceQuery, pickSliceKey } from "../../src/lib/qqbot/slice-resolve.ts";

// 真实线上数据（节选必要字段）。
const FIXTURE = [
  { rankKey: "1020001", name: "春日樱序", categories: ["异能榜", "彩金币榜"], dynamic: false, lastFetch: 1780578000360, visible: true },
  { rankKey: "1021001", name: "仲夏之梦(当前赛季)", categories: ["彩金币榜", "异能榜"], dynamic: true, lastFetch: 1781219049771, visible: true },
  { rankKey: "2034001", name: "万化无限·主宰·终极(乱斗)", categories: ["乱斗榜", "终极榜"], dynamic: false, lastFetch: 1780730165771, visible: true },
  { rankKey: "2035001", name: "断罪·终极(乱斗)", categories: ["乱斗榜", "终极榜"], dynamic: false, lastFetch: 1780658122759, visible: true },
  { rankKey: "2039001", name: "破鸿·终极(当前乱斗)", categories: ["乱斗榜", "终极榜"], dynamic: true, lastFetch: 1781219837844, visible: true },
  { rankKey: "5000253", name: "贪婪祭坛", categories: ["副本榜"], dynamic: false, lastFetch: 1780574385880, visible: true },
  { rankKey: "5005622", name: "深空争霸·S3", categories: ["深空榜"], dynamic: false, lastFetch: 1780910833147, visible: true },
];

let failed = 0;
function eq(label, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) failed++;
  console.log(`${ok ? "OK " : "BAD"} ${label}  got=${JSON.stringify(got)}${ok ? "" : ` want=${JSON.stringify(want)}`}`);
}

// 1) parseSliceQuery：核心拆分
{
  const q = parseSliceQuery("查仲夏之梦81区异能分");
  eq("parse 查仲夏之梦81区异能分 → boardHint", q.boardHint, "仲夏之梦");
  eq("parse 查仲夏之梦81区异能分 → category", q.category, "异能榜");
  eq("parse 查仲夏之梦81区异能分 → partition", q.partition, 81);
  eq("parse 查仲夏之梦81区异能分 → dragon", q.dragon, false);
}

// 2) resolve：用户原始报障 query 必须命中 1021001
function resolve(text) {
  const q = parseSliceQuery(text);
  const hit = pickSliceKey(FIXTURE, q.boardHint || (q.category ? "" : text.trim()), q.category, q.raw);
  return hit ? hit.rankKey : null;
}
eq("resolve 查仲夏之梦81区异能分", resolve("查仲夏之梦81区异能分"), "1021001");
eq("resolve 仲夏之梦异能分（粘连串）", resolve("仲夏之梦异能分"), "1021001");
eq("resolve 查仲夏之梦龙虎榜", resolve("查仲夏之梦龙虎榜"), "1021001");
eq("resolve 看看仲夏之梦37区", resolve("看看仲夏之梦37区"), "1021001");
eq("resolve 查异能榜第37区（仅分类→动态最新）", resolve("查异能榜第37区"), "1021001");
eq("resolve 断罪终极乱斗（维度词在榜名里）", resolve("看看断罪终极乱斗"), "2035001");
eq("resolve 贪婪祭坛88区", resolve("贪婪祭坛88区"), "5000253");
eq("resolve 查深空争霸", resolve("查深空争霸"), "5005622");
eq("resolve 春日樱序", resolve("查春日樱序异能"), "1020001");

// 3) partition 双向：N区 与 区N 都能解析
eq("parse 81区 → partition", parseSliceQuery("仲夏之梦81区").partition, 81);
eq("parse 区81 → partition", parseSliceQuery("仲夏之梦区81").partition, 81);
eq("parse 第81区 → partition", parseSliceQuery("仲夏之梦第81区").partition, 81);

// 4) 负例：不存在的榜 → null
eq("resolve 不存在的榜xyz → null", resolve("查不存在的榜xyz"), null);

if (failed) {
  console.error(`\n${failed} assertions FAILED`);
  process.exit(1);
}
console.log("\nAll slice-resolve assertions passed.");
