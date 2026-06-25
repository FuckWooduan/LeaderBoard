#!/usr/bin/env node
/**
 * 玩家名归一化单测（纯逻辑，无网络）。
 *
 * 复现并锁死线上 bug：「帮我查一下把心事说与风的在线情况」抠名字后残留所属助词「的」，
 * 变成「把心事说与风的」→ /player 精确匹配全区查不到。cleanPlayerName 必须把它修成「把心事说与风」。
 *
 * 运行：node scripts/qqbot/verify-clean-name.mjs
 */

import { cleanPlayerName } from "../../src/lib/qqbot/names.ts";

let failed = 0;
function eq(label, got, want) {
  const ok = got === want;
  if (!ok) failed++;
  console.log(`${ok ? "OK " : "BAD"} ${label}  got=${JSON.stringify(got)}${ok ? "" : ` want=${JSON.stringify(want)}`}`);
}

// 线上真实 bug：残留所属助词「的」
eq("把心事说与风的 → 剥的", cleanPlayerName("把心事说与风的"), "把心事说与风");
eq("张三的 → 剥的", cleanPlayerName("张三的"), "张三");
eq("把心事说与风的的 → 反复剥的", cleanPlayerName("把心事说与风的的"), "把心事说与风");

// 正常名字不动
eq("把心事说与风 → 不动", cleanPlayerName("把心事说与风"), "把心事说与风");
eq("英文名 Player_1 → 不动", cleanPlayerName("Player_1"), "Player_1");
eq("含中点 联四仙尊·夜夜 → 不动", cleanPlayerName("联四仙尊·夜夜"), "联四仙尊·夜夜");

// 边缘标点 / 引号 / 空白
eq("「张三」→ 去引号", cleanPlayerName("「张三」"), "张三");
eq("  空格张三的  → trim+剥的", cleanPlayerName("  张三的  "), "张三");
eq("张三， → 去逗号", cleanPlayerName("张三，"), "张三");

// 短名保护：剥完会 <2 字就不剥（避免把名字剥没）
eq("刀的 → 保护不剥(剥完仅1字)", cleanPlayerName("刀的"), "刀的");

// 空/脏输入
eq("空串 → 空", cleanPlayerName(""), "");
eq("纯标点 → 空", cleanPlayerName("，。"), "");
eq("undefined → 空", cleanPlayerName(undefined), "");

console.log(failed ? `\n❌ ${failed} 条断言失败` : "\n✅ 全部断言通过");
process.exit(failed ? 1 : 0);
