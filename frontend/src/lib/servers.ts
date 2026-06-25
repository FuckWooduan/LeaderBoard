/** 区服（districtId → 名称）映射与运营商配色，与后端 GameServer 对齐。 */

export const SERVERS: [number, string][] = [
  [6, "电信一区"], [8, "电信二区"], [10, "电信三区"], [12, "电信四区"], [14, "电信五区"],
  [17, "电信六区"], [22, "电信七区"], [24, "电信八区"],
  [7, "联通一区"], [9, "联通二区"], [13, "联通三区"], [16, "联通四区"], [18, "联通五区"], [28, "联通六区"],
  [5, "双线一区"], [19, "双线二区"], [31, "双线三区"], [42, "双线四区"], [45, "双线五区"], [46, "双线六区"], [52, "双线七区"],
];

export const SERVER_NAME: Record<number, string> = {};
SERVERS.forEach(([id, name]) => (SERVER_NAME[id] = name));

/**
 * 玩家「在线查询」全区扫描用的 districtId 列表（= 查询下拉框全部区服）。
 * 双线六区(46) 是合服前老区，后端 /player 已支持（无角色容忍）；已并入下拉可直接选。
 */
export const ONLINE_QUERY_DISTRICTS: string[] = SERVERS.map(([id]) => String(id));

/** 区服名 → 既定显示顺序索引（电信→联通→双线，各自升序，按 SERVERS 顺序）。 */
export const SERVER_ORDER: Record<string, number> = {};
SERVERS.forEach(([, name], i) => (SERVER_ORDER[name] = i));

/** 运营商色：[前景, 背景]（背景用 color-mix 透明度适配暗色主题）。 */
export function operatorColor(name?: string): [string, string] {
  if (!name) return ["#94a3b8", "rgba(148,163,184,0.14)"];
  if (name.startsWith("电信")) return ["#34d399", "rgba(52,211,153,0.14)"];
  if (name.startsWith("联通")) return ["#60a5fa", "rgba(96,165,250,0.14)"];
  if (name.startsWith("双线")) return ["#a78bfa", "rgba(167,139,250,0.16)"];
  return ["#94a3b8", "rgba(148,163,184,0.14)"];
}
