/** 时间/数字格式化（与旧版 app.js 行为一致）。 */

/** 强制北京时间(UTC+8)显示，不随访问者时区变化，统一为 2026-06-10 08:28。 */
export function fmtTime(ms?: number | string | null): string {
  return fmtFullTime(ms);
}

/** 含年份的完整北京时间。 */
export function fmtFullTime(ms?: number | string | null): string {
  if (ms == null || ms === "" || Number(ms) === 0) return "—";
  try {
    const d = new Date(Number(ms) + 8 * 3600 * 1000);
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}-${String(d.getUTCDate()).padStart(
      2,
      "0"
    )} ${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
  } catch {
    return "—";
  }
}

/** 千分位。 */
export function fmtNum(v: unknown): string {
  if (v === null || v === undefined || v === "") return "";
  const n = Number(v);
  if (isNaN(n) || !isFinite(n)) return String(v);
  return n.toLocaleString("en-US");
}
