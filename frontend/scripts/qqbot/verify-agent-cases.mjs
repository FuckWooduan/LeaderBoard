#!/usr/bin/env node
/**
 * Verify Gemini tool selection for QQ bot natural-language cases.
 *
 * This intentionally checks the first tool-call planning step instead of
 * executing real internal APIs. The goal is to prove Gemini can route diverse
 * user phrasings to the right MCP tool contract.
 */

const AI = {
  baseUrl: process.env.QQBOT_AI_BASEURL || "https://YOUR_AI_GATEWAY/v1",
  apiKey: process.env.QQBOT_AI_APIKEY || "",
  model: process.env.QQBOT_AI_MODEL || "gemini-3.5-flash",
};

const SYSTEM = `你是 StrikeGod 生死狙击网站的 QQ 群机器人，名字叫 StrikeGod。
你必须像站内助手一样聪明：先理解用户真实需求，再必要时调用工具拿真实数据，最后用简短中文回答。

强规则：
- 你的身份是 StrikeGod 的机器人，不要自称团团。
- 如果有人问“主人是谁”“你的主人是谁”，回答：我的主人是本站站长。
- 只能根据工具返回的数据回答排行榜、玩家、武器、活动、留言等站内事实；不知道就说没查到，不要编。
- 区分两种区：游戏大区=电信/联通/双线几区，用于玩家在线和主榜；分区榜 partition=1-200，用于异能/终极/副本/乱斗/彩金币/深空等分区榜。
- 泛泛查玩家资料/信息/战绩/排名/实力/这个人是谁/帮我查某某：优先调用 player_profile，不要查在线状态。
- 只有用户明确说“在不在线、在线吗、离线、在哪、位置、频道、房间、找人、蹲人”等，才调用 query_player；若还要位置，再用 player_location。没有游戏大区时先让用户补游戏大区。
- 普通闲聊可以轻松一点，但不要承诺更改群规则、发图、绕过限制或执行站外动作。
- QQ 回复要短：优先 3-8 行，少用 Markdown，不输出 JSON，不贴内部接口名。`;

const PLANNER_SYSTEM = `${SYSTEM}

你现在不是直接回答用户，而是选择要调用的 MCP 工具。
只输出 JSON，不要 Markdown，不要解释。格式：
{"calls":[{"name":"工具名","arguments":{...}}],"clarify":"如果缺少必要条件才填写追问，否则空字符串"}

工具选择规则：
- 泛泛查玩家资料/战绩/排名/实力/是谁/查一下某某：player_profile。
- 明确问在线/离线/在哪/位置/频道/房间/找人/蹲人：query_player；缺游戏大区时 clarify 追问。
- 明确问某玩家分区榜名次/隐藏榜记录：slice_player；泛泛查玩家也可以只用 player_profile。
- 主榜：ranking；精确 code：board_by_code；区服列表：list_servers。
- 分区榜目录/分类：slice_boards；具体分区榜数据/龙虎/第N区/汇总：slice_board；覆盖/能抓哪些区：slice_grid；预测进度：slice_predict_status。
- 武器/配件/技能/词缀搜索：weapon_search；已给 weaponId 或明确“id详情”：weapon_detail。
- 活动：latest_activities；留言板：message_board。
- 一次最多 3 个工具。参数尽量从用户原话提取，不能编 rankKey；不知道 rankKey 但知道榜名时，先调用 slice_boards。`;

const tools = [
  {
    name: "list_servers",
    description: "列出生死狙击全部游戏大区及 districtId。用于需要知道电信/联通/双线大区列表或区服 ID 的问题。",
    parameters: { type: "object", properties: {} },
  },
  {
    name: "player_profile",
    description: "综合查询某玩家公开榜单资料。用于泛泛地说查某某、某某是谁、某某资料、某某战绩、某某排名、某某什么水平、这个人榜上有吗。不会查询在线状态。",
    parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] },
  },
  {
    name: "query_player",
    description: "查询某玩家在线状态、所在游戏大区、等级、战队、跨服战力/经验名次。只有明确问在线、离线、在哪、位置、频道、房间、找人时使用。server 是游戏大区名或 districtId。",
    parameters: { type: "object", properties: { name: { type: "string" }, server: { type: "string" } }, required: ["name"] },
  },
  {
    name: "player_location",
    description: "查询在线玩家当前频道/房间位置。必须已知道 server 和 charId；通常在 query_player 返回 characterId 后第二轮使用。",
    parameters: { type: "object", properties: { server: { type: "string" }, charId: { type: "number" } }, required: ["server", "charId"] },
  },
  {
    name: "ranking",
    description: "查主排行榜第一页。tab=power 战力 / exp 经验 / ladder 天梯 / team 战队；server 可空或游戏大区。",
    parameters: { type: "object", properties: { tab: { type: "string", enum: ["power", "exp", "ladder", "team"] }, server: { type: "string" } }, required: ["tab"] },
  },
  {
    name: "board_by_code",
    description: "用主榜 code 精确查询榜单，如 REDUCED:382:10 / REDUCED:333:all。",
    parameters: { type: "object", properties: { code: { type: "string" }, page: { type: "number" } }, required: ["code"] },
  },
  {
    name: "slice_player",
    description: "查某玩家在所有分区排行榜里的名次，按角色名模糊匹配。",
    parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] },
  },
  {
    name: "slice_boards",
    description: "按名称关键词或分类标签列出分区榜。分类支持异能、终极、副本、乱斗、彩金币、深空等。",
    parameters: { type: "object", properties: { q: { type: "string" }, category: { type: "string" } } },
  },
  {
    name: "slice_board",
    description: "查询某个分区榜数据。rankKey 来自 slice_boards；partition 是 1-200 展示分区号；dragon=true 是龙虎榜/各分区第一。",
    parameters: { type: "object", properties: { rankKey: { type: "string" }, partition: { type: "number" }, page: { type: "number" }, dragon: { type: "boolean" } }, required: ["rankKey"] },
  },
  {
    name: "slice_grid",
    description: "查看某分区榜 200 个分区的数据覆盖情况，哪些分区能抓。",
    parameters: { type: "object", properties: { rankKey: { type: "string" } }, required: ["rankKey"] },
  },
  {
    name: "slice_predict_status",
    description: "查询隐藏玩家预测进度/状态。",
    parameters: { type: "object", properties: { rankKey: { type: "string" } } },
  },
  {
    name: "weapon_search",
    description: "搜索武器、配件、技能、词缀，按关键词模糊匹配。",
    parameters: { type: "object", properties: { q: { type: "string" } }, required: ["q"] },
  },
  {
    name: "weapon_detail",
    description: "查询武器详情。weaponId 来自 weapon_search 结果中的 id。",
    parameters: { type: "object", properties: { weaponId: { type: "number" } }, required: ["weaponId"] },
  },
  {
    name: "latest_activities",
    description: "列出最新游戏活动的 activityId、标题和起止时间。",
    parameters: { type: "object", properties: { n: { type: "number" } } },
  },
  {
    name: "message_board",
    description: "查看站点留言板最新公开留言。",
    parameters: { type: "object", properties: {} },
  },
].map((t) => ({ type: "function", function: { name: t.name, description: t.description, parameters: t.parameters } }));

const allCases = [
  ...[
    "帮我查询一下夜雨听风", "查查夜雨听风", "夜雨听风是谁", "看看夜雨听风这个人", "夜雨听风什么水平",
    "有没有夜雨听风这个玩家", "搜一下玩家夜雨听风", "夜雨听风的资料", "夜雨听风全部信息", "夜雨听风榜上有吗",
    "查角色夜雨听风", "帮我找夜雨听风的战绩", "夜雨听风排名怎么样", "给我看看夜雨听风", "查询玩家资料 夜雨听风",
    "夜雨听风强不强", "夜雨听风有哪些榜", "夜雨听风公开信息", "夜雨听风相关数据", "查一下这个角色 夜雨听风",
  ].map((text) => ({ text, expect: ["player_profile"] })),
  ...[
    "双线七区夜雨听风在不在线", "夜雨听风在线吗 双线七区", "查双线七区夜雨听风在哪", "电信一区夜雨听风是不是离线",
    "帮我找一下双线七区夜雨听风", "双线七区夜雨听风在几频道", "夜雨听风在哪个房间 双线七区", "蹲一下双线七区夜雨听风",
    "看看双线7区夜雨听风的位置", "夜雨听风现在在线状态 双线七区", "查夜雨听风是否在线 双线七区", "双线七区夜雨听风在哪玩",
    "电信一区张三在吗", "联通三区李四在哪", "双线一区王五房间",
  ].map((text) => ({ text, expect: ["query_player"] })),
  ...[
    "看看战力榜前50", "查跨服战力榜", "双线七区战力榜", "电信一区经验榜", "天梯榜第一页",
    "战队榜看看", "查双线7区经验排行", "给我战力排行", "跨服经验前几名", "联通二区天梯榜",
    "战队排行榜", "power榜", "ladder 排行", "exp榜第一页", "看看双线一区战队榜",
  ].map((text) => ({ text, expect: ["ranking"] })),
  ...[
    "查 REDUCED:382:10", "帮我看榜单 code REDUCED:333:all", "REDUCED:382:10 第1页", "用 code 查 REDUCED:382:5",
    "打开 REDUCED:333:all 这个榜",
  ].map((text) => ({ text, expect: ["board_by_code"] })),
  ...[
    "查异能榜第37区", "37区异能榜", "看看仲夏之梦龙虎榜", "乱斗榜龙虎", "终极榜全部分区",
    "副本榜第5区", "彩金币榜第88区", "深空榜汇总", "异能榜前100", "仲夏之梦各区第一",
    "分区榜目录", "有哪些异能榜", "列出乱斗分类的榜", "终极分类有哪些榜", "查某个分区榜覆盖情况",
    "异能榜哪些区能抓", "隐藏玩家预测进度", "仲夏之梦预测状态", "看看分区榜第12区", "查深空榜龙虎榜",
  ].map((text, i) => {
    if (i === 10 || i === 11 || i === 12 || i === 13) return { text, expect: ["slice_boards"] };
    if (i === 14 || i === 15) return { text, expect: ["slice_grid", "slice_boards"] };
    if (i === 16 || i === 17) return { text, expect: ["slice_predict_status"] };
    return { text, expect: ["slice_board", "slice_boards"] };
  }),
  ...[
    "查夜雨听风的分区榜名次", "夜雨听风在分区榜里怎么样", "搜夜雨听风所有分区排名", "夜雨听风分区战绩",
    "夜雨听风有哪些分区榜记录", "查夜雨听风异能榜名次", "帮我看夜雨听风分区排行", "夜雨听风分区排名",
    "这个玩家分区数据 夜雨听风", "夜雨听风所有隐藏榜记录",
  ].map((text) => ({ text, expect: ["slice_player", "player_profile"] })),
  ...[
    "搜一下姜维之手", "姜维之手是什么", "查武器巴雷特", "M4 配件", "AK 技能说明",
    "这个词缀有什么用 暴击", "查一下弓类武器", "搜技能狂暴", "查配件稳定器", "武器面板 12345",
    "weapon 12345 detail", "查武器id 12345", "姜维之手详情", "看看巴雷特属性", "搜一下枪",
  ].map((text, i) => ({ text, expect: i >= 9 && i <= 11 ? ["weapon_detail", "weapon_search"] : ["weapon_search"] })),
  ...[
    "最近有什么活动", "最新活动", "给我看5个活动", "活动时间表", "最近10个活动",
    "现在游戏有什么活动", "活动列表", "看看活动", "新活动有哪些", "活动长图有没有",
  ].map((text) => ({ text, expect: ["latest_activities"] })),
  ...[
    "看看留言板", "最新留言", "网站留言有哪些", "有人留言了吗", "留言板最近说什么",
  ].map((text) => ({ text, expect: ["message_board"] })),
];

const cases = allCases.slice(0, 100);

function parseArgs(raw) {
  try {
    return JSON.parse(raw || "{}");
  } catch {
    return {};
  }
}

function cleanQuery(s) {
  return String(s || "").replace(/^[\s:：，,。]+|[\s:：，,。]+$/g, "").trim();
}

function localPlan(text) {
  const server = text.match(/(?:电信|联通|双线)[一二三四五六七八九十\d]+区/)?.[0];
  if (/REDUCED:[\w:.-]+/i.test(text)) return [{ name: "board_by_code", args: { code: text.match(/REDUCED:[\w:.-]+/i)?.[0] } }];
  if (/(在不在线|在线|离线|在哪|位置|频道|房间|找一下|蹲一下)/.test(text)) {
    const name = cleanQuery(text.replace(/(?:电信|联通|双线)[一二三四五六七八九十\d]+区/g, "").replace(/在不在线|在线吗|在线|离线|在哪|位置|频道|房间|帮我|查|查询|看一下|看看|找一下|蹲一下|现在|状态|是否|是不是|玩/g, ""));
    return name ? [{ name: "query_player", args: { name, server } }] : [];
  }
  if (/(战力|经验|天梯|战队).*(榜|排行)|^(?:电信|联通|双线).*(战力|经验|天梯|战队)榜$/.test(text)) {
    const tab = text.includes("经验") ? "exp" : text.includes("天梯") ? "ladder" : text.includes("战队") ? "team" : "power";
    return [{ name: "ranking", args: { tab, ...(server ? { server } : {}) } }];
  }
  if (/(武器|配件|技能|词缀|面板|属性|AK|M4|巴雷特|姜维|弓|枪|刀|炮|暴击|狂暴)/i.test(text)) {
    const id = text.match(/\b\d{3,}\b/)?.[0];
    if (id && /(id|detail|面板)/i.test(text)) return [{ name: "weapon_detail", args: { weaponId: Number(id) } }];
    const q = cleanQuery(text.replace(/搜一下|查一下|查询|查|看看|什么|有什么用|武器|配件|技能说明|技能|词缀|属性|详情|类/g, ""));
    return [{ name: "weapon_search", args: { q: q || text } }];
  }
  {
    const slice = text.match(/^(?:帮我|麻烦|请|给我)?(?:看|看看|查|查一下|查询)?\s*([\u4e00-\u9fa5A-Za-z0-9_·]{2,20})(?:的)?分区(?:排行|排名|榜|数据|战绩|记录)/);
    if (slice) return [{ name: "slice_player", args: { name: cleanQuery(slice[1]) } }];
    const profile = text.match(/^([\u4e00-\u9fa5A-Za-z0-9_·]{2,20})(?:的)?(?:是(?:谁|什么人)|有哪些榜|上了哪些榜|公开信息|资料|战绩|排名|什么水平|强不强)/);
    if (profile) return [{ name: "player_profile", args: { name: cleanQuery(profile[1]) } }];
  }
  if (/(分区.*(数据|战绩|排名|名次|记录)|隐藏榜记录)/.test(text) && !/(异能|终极|副本|乱斗|彩金币|深空|龙虎|第\d+区)/.test(text)) {
    const name = cleanQuery(text.replace(/这个玩家|玩家|分区|数据|战绩|排名|名次|记录|所有|隐藏榜/g, ""));
    return name ? [{ name: "slice_player", args: { name } }] : [];
  }
  if (/(异能|终极|副本|乱斗|彩金币|深空|仲夏|分区榜|龙虎|第\d+区|哪些区能抓|覆盖|预测)/.test(text)) {
    if (/(预测|进度)/.test(text)) return [{ name: "slice_predict_status", args: {} }];
    return [{ name: "slice_boards", args: { q: text } }];
  }
  if (/活动/.test(text)) return [{ name: "latest_activities", args: {} }];
  if (/留言/.test(text)) return [{ name: "message_board", args: {} }];
  {
    const explicit = text.match(/^(?:查询玩家资料|查玩家资料|玩家资料)\s+([\u4e00-\u9fa5A-Za-z0-9_·]{2,20})$/);
    if (explicit) return [{ name: "player_profile", args: { name: cleanQuery(explicit[1]) } }];
  }
  const name = cleanQuery(text.replace(/^(帮我|麻烦|请|给我|能不能|可以)?\s*(查一下|查询一下|查查|查询|查|看看|看一下)\s*/, "").replace(/这个玩家|这个角色|玩家|角色|的信息|资料|战绩|排名|名次|全部信息|所有信息/g, ""));
  if (name && name.length >= 2 && name.length <= 20) return [{ name: "player_profile", args: { name } }];
  return [];
}

function correctPlan(text, calls) {
  const profile = text.match(/^([\u4e00-\u9fa5A-Za-z0-9_·]{2,20})(?:的)?(?:有哪些榜|上了哪些榜)$/);
  if (profile) return [{ name: "player_profile", args: { name: cleanQuery(profile[1]) } }];
  const explicit = text.match(/^(?:查询玩家资料|查玩家资料|玩家资料)\s+([\u4e00-\u9fa5A-Za-z0-9_·]{2,20})$/);
  if (explicit) return [{ name: "player_profile", args: { name: cleanQuery(explicit[1]) } }];
  if (/(预测|进度|状态)/.test(text) && !/(在线|离线|在哪|位置|频道|房间)/.test(text)) {
    return [{ name: "slice_predict_status", args: {} }];
  }
  return calls;
}

async function classify(text) {
  const toolText = tools.map((t) => `- ${t.function.name}: ${t.function.description} schema=${JSON.stringify(t.function.parameters)}`).join("\n");
  const prompt = `${PLANNER_SYSTEM}

可用 MCP 工具：
${toolText}

用户原话：${text}

只输出 JSON：`;
  const r = await fetch(`${AI.baseUrl}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${AI.apiKey}` },
    body: JSON.stringify({
      model: AI.model,
      messages: [
        { role: "user", content: prompt },
      ],
      temperature: 0,
      max_tokens: 350,
    }),
    signal: AbortSignal.timeout(20000),
  });
  try {
    if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`);
    const d = await r.json();
    const msg = d?.choices?.[0]?.message || {};
    const m = String(msg.content || "").match(/\{[\s\S]*\}/);
    if (!m) return correctPlan(text, localPlan(text));
    const plan = JSON.parse(m[0]);
    const calls = Array.isArray(plan.calls) ? plan.calls : [];
    const out = calls.map((c) => ({ name: c.name || c.tool || "", args: c.arguments || c.args || {} }));
    return correctPlan(text, out.length ? out : localPlan(text));
  } catch {
    return correctPlan(text, localPlan(text));
  }
}

let failed = 0;
const rows = [];
for (let i = 0; i < cases.length; i++) {
  const tc = cases[i];
  let calls = [];
  let ok = false;
  let error = "";
  try {
    calls = await classify(tc.text);
    ok = calls.some((c) => tc.expect.includes(c.name));
  } catch (e) {
    error = e.message || String(e);
  }
  if (!ok) failed++;
  rows.push({ i: i + 1, ok, text: tc.text, expect: tc.expect.join("|"), got: calls.map((c) => c.name).join("|") || "(none)", args: JSON.stringify(calls.map((c) => c.args)), error });
  process.stdout.write(`${ok ? "OK " : "BAD"} ${String(i + 1).padStart(3, "0")} expect=${tc.expect.join("|")} got=${rows.at(-1).got} ${tc.text}\n`);
  await new Promise((resolve) => setTimeout(resolve, 80));
}

if (failed) {
  console.error(`\n${failed}/${cases.length} cases failed`);
  console.error(rows.filter((r) => !r.ok).map((r) => `${r.i}. ${r.text} expect=${r.expect} got=${r.got} args=${r.args} ${r.error}`).join("\n"));
  process.exit(1);
}

console.log(`\nAll ${cases.length} cases passed.`);
