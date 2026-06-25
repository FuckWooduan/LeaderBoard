/**
 * 公开 MCP server（Model Context Protocol，JSON-RPC 2.0，只读）。
 *
 * 供 AI/MCP 客户端接入 example.com 的公开查询能力。所有工具都转调**公开**后端 API
 * （http://127.0.0.1:8080/api/public/*）；玩家在线查询走公开 API Key 通道 /api/key/player
 * （需服务端配置 MCP_API_KEY，否则该工具返回未启用提示，其余工具不受影响）。
 *
 * 方法：initialize / tools/list / tools/call / ping。通知（无 id，如 notifications/initialized）回 202。
 *
 * 关键语义（写进工具描述，避免模型混淆）：
 *   - 「游戏大区」server：电信/联通/双线大区，主榜与玩家查询用。loginId（/list_servers 的 id）↔ districtId（玩家查询用）。
 *   - 「分区榜分区」partition：某个分区榜内部 1-200 的分组，与游戏大区无关。
 */

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const API = process.env.API_BASE || "http://127.0.0.1:8080";
const MCP_API_KEY = process.env.MCP_API_KEY || "";

const PROTOCOL_VERSION = "2024-11-05";
const SERVER_INFO = { name: "strikegod-rank", version: "1.0.0" };

type Json = Record<string, unknown>;

function enc(v: unknown): string {
  return encodeURIComponent(String(v ?? ""));
}

async function backend(path: string, headers?: Record<string, string>): Promise<unknown> {
  const r = await fetch(API + path, { headers, cache: "no-store" });
  const text = await r.text();
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    data = text;
  }
  if (!r.ok) {
    return { error: `后端 ${r.status}`, detail: data };
  }
  return data;
}

// ── 工具注册表 ─────────────────────────────────────────────────────────────
interface Tool {
  name: string;
  description: string;
  inputSchema: Json;
  run: (args: Json) => Promise<unknown>;
}

const TOOLS: Tool[] = [
  {
    name: "list_servers",
    description:
      "列出某榜标签下可选的游戏大区（返回 id=loginId + name）。tab=power(战力)/exp(经验)/ladder(天梯)。返回的 id 可直接给 ranking 的 server。",
    inputSchema: {
      type: "object",
      properties: { tab: { type: "string", enum: ["power", "exp", "ladder"], description: "榜标签" } },
      required: ["tab"],
    },
    run: (a) => backend(`/api/public/servers?tab=${enc(a.tab)}`),
  },
  {
    name: "ranking",
    description:
      "查主排行榜分页（每页100）。tab=power/exp/ladder/team；server 为游戏大区 loginId（来自 list_servers），空=全部/跨服；可选 q 模糊搜名、page。注意 server 是游戏大区，不是分区榜的 partition。",
    inputSchema: {
      type: "object",
      properties: {
        tab: { type: "string", enum: ["power", "exp", "ladder", "team"] },
        server: { type: "string", description: "游戏大区 loginId；空=全部/跨服" },
        page: { type: "integer", minimum: 1, default: 1 },
        q: { type: "string", description: "按角色名模糊搜索（名次不变）" },
      },
      required: ["tab"],
    },
    run: (a) =>
      backend(
        `/api/public/tab?tab=${enc(a.tab)}&server=${enc(a.server ?? "")}&page=${enc(a.page ?? 1)}` +
          (a.q ? `&q=${enc(a.q)}` : ""),
      ),
  },
  {
    name: "list_boards",
    description: "列出当前对外可见的所有榜（code/type/kind/server），用于枚举/调试。",
    inputSchema: { type: "object", properties: {} },
    run: () => backend(`/api/public/boards`),
  },
  {
    name: "search_player",
    description:
      "查玩家在线状态与主榜名次。name=角色名（精确），server=对外服号 districtId（玩家在游戏客户端看到的服号，非 loginId）。需服务端配置 MCP_API_KEY。",
    inputSchema: {
      type: "object",
      properties: {
        name: { type: "string", description: "角色名（精确）" },
        server: { type: "string", description: "对外服号 districtId" },
      },
      required: ["name", "server"],
    },
    run: (a) => {
      if (!MCP_API_KEY) {
        return Promise.resolve({ error: "玩家查询未启用：服务端未配置 MCP_API_KEY（其余工具不受影响）" });
      }
      return backend(`/api/key/player?name=${enc(a.name)}&server=${enc(a.server)}`, { "X-API-Key": MCP_API_KEY });
    },
  },
  {
    name: "slice_boards",
    description:
      "按名称或分类标签找分区榜（返回 SliceKey 列表）。category 可选：异能/终极/副本/乱斗/彩金币/深空/自定义。分区榜与游戏大区无关。",
    inputSchema: {
      type: "object",
      properties: { category: { type: "string", description: "分类标签模糊筛选（可选）" } },
    },
    run: (a) => backend(`/api/public/slice/keys${a.category ? `?category=${enc(a.category)}` : ""}`),
  },
  {
    name: "slice_board",
    description:
      "查某分区榜分页数据。rankKey 来自 slice_boards；partition 是 0-based 分区号（0=第1区，199=第200区；自然语言『37区』应转成 partition=36），不传=汇总；可选 page。partition 是榜内分区，不是游戏大区。",
    inputSchema: {
      type: "object",
      properties: {
        rankKey: { type: "string" },
        partition: { type: "integer", minimum: 0, maximum: 199, description: "0-based 分区号；不传=汇总" },
        page: { type: "integer", minimum: 1, default: 1 },
      },
      required: ["rankKey"],
    },
    run: (a) =>
      backend(
        `/api/public/slice/board?rankKey=${enc(a.rankKey)}&page=${enc(a.page ?? 1)}` +
          (a.partition != null ? `&partition=${enc(a.partition)}` : ""),
      ),
  },
  {
    name: "slice_player",
    description: "按角色名模糊匹配，查玩家在全部分区榜里的名次。",
    inputSchema: {
      type: "object",
      properties: { name: { type: "string", description: "角色名（模糊）" } },
      required: ["name"],
    },
    run: (a) => backend(`/api/public/slice/player?name=${enc(a.name)}`),
  },
  {
    name: "weapon_search",
    description: "搜索武器/配件/技能/词缀（按关键词）。",
    inputSchema: {
      type: "object",
      properties: { q: { type: "string", description: "关键词" } },
      required: ["q"],
    },
    run: (a) => backend(`/api/public/weapon/search?q=${enc(a.q)}`),
  },
  {
    name: "weapon_detail",
    description: "按武器 id 查完整面板（弹匣/伤害/配件/技能等）。id 来自 weapon_search。",
    inputSchema: {
      type: "object",
      properties: { id: { type: "string", description: "武器 id" } },
      required: ["id"],
    },
    run: (a) => backend(`/api/public/weapon/${enc(a.id)}`),
  },
  {
    name: "list_activities",
    description: "查活动列表（分页，新→旧）。可选 q 按标题/描述搜索、page、size。",
    inputSchema: {
      type: "object",
      properties: {
        page: { type: "integer", minimum: 0, default: 0 },
        size: { type: "integer", minimum: 1, maximum: 60, default: 24 },
        q: { type: "string" },
      },
    },
    run: (a) =>
      backend(
        `/api/public/activities?page=${enc(a.page ?? 0)}&size=${enc(a.size ?? 24)}` + (a.q ? `&q=${enc(a.q)}` : ""),
      ),
  },
  {
    name: "activity_feed",
    description:
      "新活动轮询 feed（游标式）。首次不带 since 取基线（isBaseline=true，仅记 nextSince 不推送），之后带 since=上次 nextSince 取增量（含活动长图 imageUrl）。",
    inputSchema: {
      type: "object",
      properties: {
        since: { type: "integer", description: "上次 nextSince；不传=基线" },
        limit: { type: "integer", minimum: 1, maximum: 60, default: 20 },
      },
    },
    run: (a) =>
      backend(`/api/public/activity/feed?limit=${enc(a.limit ?? 20)}` + (a.since != null ? `&since=${enc(a.since)}` : "")),
  },
];

const TOOL_MAP = new Map(TOOLS.map((t) => [t.name, t]));

// ── JSON-RPC ────────────────────────────────────────────────────────────────
function rpcResult(id: unknown, result: unknown): Json {
  return { jsonrpc: "2.0", id, result };
}
function rpcError(id: unknown, code: number, message: string): Json {
  return { jsonrpc: "2.0", id, error: { code, message } };
}

async function handleOne(msg: Json): Promise<Json | null> {
  const { id, method, params } = msg as { id?: unknown; method?: string; params?: Json };
  // 通知（无 id）：不回结果
  if (id === undefined || id === null) {
    return null;
  }
  switch (method) {
    case "initialize":
      return rpcResult(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: SERVER_INFO,
      });
    case "ping":
      return rpcResult(id, {});
    case "tools/list":
      return rpcResult(id, {
        tools: TOOLS.map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })),
      });
    case "tools/call": {
      const name = (params?.name as string) || "";
      const args = (params?.arguments as Json) || {};
      const tool = TOOL_MAP.get(name);
      if (!tool) {
        return rpcError(id, -32602, `未知工具: ${name}`);
      }
      try {
        const data = await tool.run(args);
        return rpcResult(id, {
          content: [{ type: "text", text: JSON.stringify(data) }],
        });
      } catch (e: unknown) {
        return rpcResult(id, {
          isError: true,
          content: [{ type: "text", text: `工具执行失败: ${e instanceof Error ? e.message : String(e)}` }],
        });
      }
    }
    default:
      return rpcError(id, -32601, `不支持的方法: ${method}`);
  }
}

export async function POST(req: Request): Promise<Response> {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return Response.json(rpcError(null, -32700, "无法解析 JSON"), { status: 400 });
  }
  // 批量请求
  if (Array.isArray(body)) {
    const out = (await Promise.all(body.map((m) => handleOne(m as Json)))).filter((x): x is Json => x !== null);
    if (out.length === 0) {
      return new Response(null, { status: 202 });
    }
    return Response.json(out);
  }
  const res = await handleOne(body as Json);
  if (res === null) {
    return new Response(null, { status: 202 }); // 通知，无响应体
  }
  return Response.json(res);
}

export async function GET(): Promise<Response> {
  return Response.json({
    name: SERVER_INFO.name,
    protocol: "MCP / JSON-RPC 2.0",
    usage: "POST JSON-RPC 到本端点：initialize / tools/list / tools/call",
    tools: TOOLS.map((t) => t.name),
  });
}
