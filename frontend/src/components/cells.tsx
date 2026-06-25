/** 榜单单元格渲染（与旧版 app.js renderCell 等价的 React 实现）。 */

import type { ReactNode } from "react";
import { fmtNum, fmtTime } from "@/lib/format";
import { SERVER_NAME, operatorColor } from "@/lib/servers";

export interface Field {
  key: string;
  label: string;
  render?: string;
}

export type Row = Record<string, any>;

const REALM: Record<number, [string, string, string]> = {
  0: ["境界I·荣光之主", "#22d3ee", "rgba(34,211,238,0.13)"],
  1: ["境界II·恒星之主", "#a78bfa", "rgba(167,139,250,0.15)"],
  2: ["境界III·星界域主", "#fbbf24", "rgba(251,191,36,0.14)"],
};

export function RealmTag({ v }: { v: any }) {
  const m = REALM[Number(v)];
  if (!m) return <>{String(v ?? "")}</>;
  return (
    <span className="badge" style={{ color: m[1], background: m[2] }}>
      {m[0]}
    </span>
  );
}

/** VIP 0→18 单一色带连续插值（灰→蓝→紫→橙→红）。 */
const VIP_STOPS: [number, [number, number, number]][] = [
  [0, [128, 136, 150]],
  [0.28, [59, 130, 246]],
  [0.5, [139, 92, 246]],
  [0.72, [249, 115, 22]],
  [1, [239, 68, 68]],
];

function vipColor(t: number): string {
  for (let i = 1; i < VIP_STOPS.length; i++) {
    if (t <= VIP_STOPS[i][0]) {
      const a = VIP_STOPS[i - 1];
      const b = VIP_STOPS[i];
      const lt = (t - a[0]) / (b[0] - a[0]);
      const c = a[1].map((av, j) => Math.round(av + (b[1][j] - av) * lt));
      return `rgb(${c[0]},${c[1]},${c[2]})`;
    }
  }
  return "rgb(239,68,68)";
}

export function VipTag({ v }: { v: any }) {
  const n = Number(v);
  if (isNaN(n)) return <>{String(v ?? "")}</>;
  const t = Math.max(0, Math.min(1, n / 18));
  return (
    <span
      style={{
        display: "inline-block",
        padding: "2px 10px",
        borderRadius: 999,
        fontSize: 12.5,
        color: "#fff",
        fontWeight: 600,
        background: vipColor(t),
      }}
    >
      V{n}
    </span>
  );
}

export function DistrictTag({ v }: { v: any }) {
  const name = SERVER_NAME[Number(v)] || `${v}区`;
  const [fg, bg] = operatorColor(name);
  return (
    <span className="badge" style={{ color: fg, background: bg }}>
      {name}
    </span>
  );
}

function tierClass(rank: number, total: number): string {
  if (!total || !rank) return "";
  const p = rank / total;
  if (p <= 0.1) return "t1";
  if (p <= 0.25) return "t2";
  if (p <= 0.5) return "t3";
  return "";
}

/** 隐藏玩家身份预测：预测名 + 徽章 + 置信度。 */
function PredictTag({ row }: { row: Row }) {
  const conf = Number(row.predicted_confidence) || 0;
  const pct = Math.round(conf * 100);
  const col = conf >= 0.8 ? "#10b981" : conf >= 0.6 ? "#d97706" : "#94a3b8";
  const label = row.predicted_source === "AI" ? "AI预测" : "预测";
  // 用淡底色 + 同色文字的低调徽章（与其它 .badge 一致），不再用纯亮色实心块。
  return (
    <span style={conf < 0.6 ? { opacity: 0.85 } : undefined} title={row.predicted_via || ""}>
      {row.predicted_name}{" "}
      <span
        className="badge"
        style={{ fontSize: 11, color: col, background: `color-mix(in srgb, ${col} 14%, transparent)` }}
      >
        {label} {pct}%
      </span>
    </span>
  );
}

/** 战力/VIP/境界关联缺失的提示（分区榜：玩家未进其区服战力榜前 1000 时后端打 power_missing 标）。 */
const POWER_MISSING_TIP =
  "该玩家未进入其所在区服的战力榜前 1000（小区常见），按 gcid 关联不到战力 / VIP / 境界数据";

/** 渲染一个单元格内容（不含 <td> 包裹）。onLongText 用于点击查看全文。 */
export function renderCellContent(
  f: Field,
  row: Row,
  total: number,
  onLongText?: (text: string) => void
): { node: ReactNode; className?: string } {
  let v = row[f.key];
  if (v === null || v === undefined) v = "";

  // 分区榜战力关联列：缺数据时明确告知原因（VIP=0 是合法值，须以 power_missing 标志区分而非空值）
  if (row.power_missing && (f.key === "power_number" || f.key === "power_vip" || f.key === "power_realm")) {
    return {
      node: (
        <span
          title={POWER_MISSING_TIP}
          style={{
            color: "var(--muted)",
            fontSize: 12,
            cursor: "help",
            borderBottom: "1px dashed var(--border-strong)",
          }}
        >
          未上榜
        </span>
      ),
    };
  }

  if (f.key === "char_name" && row.is_prediction && row.predicted_name) {
    return { node: <PredictTag row={row} /> };
  }
  if (f.key === "char_name" && v === "" && Boolean(row.display) === false) {
    return {
      node: (
        <span style={{ color: "var(--danger)" }} title="隐藏玩家，暂无法预测（未设上一赛季或链路断）">
          🔒 隐藏
        </span>
      ),
    };
  }
  if (f.key === "rank") {
    // 奖牌按 medal_rank（分区榜汇总/龙虎榜=全局位次；其余=真实名次），名次列仍显示原 rank 值
    const mr = typeof row.medal_rank === "number" ? row.medal_rank : Number(v);
    const medal = mr === 1 ? "🥇" : mr === 2 ? "🥈" : mr === 3 ? "🥉" : "";
    return {
      className: "rank num",
      node: (
        <>
          {medal && <span className="medal">{medal} </span>}
          {String(v)}
          {row._fidx ? <span className="fidx"> ({row._fidx})</span> : null}
        </>
      ),
    };
  }
  if (f.render === "LONGTEXT") {
    const sv = String(v);
    if (!sv) return { node: <span style={{ color: "var(--muted)" }}>—</span> };
    const short = sv.length > 10 ? sv.slice(0, 10) + "…" : sv;
    return {
      node: (
        <span className="longtext" onClick={() => onLongText?.(sv)}>
          {short}
        </span>
      ),
    };
  }
  if (f.render === "TIME") {
    return { node: <span style={{ color: "var(--muted)", fontSize: 13 }}>{v ? fmtTime(v) : "—"}</span> };
  }
  if (f.render === "REALM") return { node: <RealmTag v={v} /> };
  if (f.render === "VIP") return { node: <VipTag v={v} /> };
  if (f.render === "DISTRICT") return { node: <DistrictTag v={v} /> };
  if (f.render === "NUMBER") {
    return { className: "num", node: <span className={tierClass(row.rank, total)}>{fmtNum(v)}</span> };
  }
  return { node: String(v) };
}
