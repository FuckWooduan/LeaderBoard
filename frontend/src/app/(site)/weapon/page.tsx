"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { fmtFullTime, fmtNum } from "@/lib/format";
import { Pager } from "@/components/Pager";

/** 游戏 HTML 净化：<font color>→<span>，平衡未闭合标签，仅保留 span/br/b/i。 */
function gameHtml(s?: string): string {
  if (!s) return "";
  let depth = 0;
  let out = s.replace(/<font\b[^>]*?color\s*=\s*["']?(#?[0-9a-zA-Z]+)["']?[^>]*>/gi, (_, col) => {
    depth++;
    return `<span style="color:${col}">`;
  });
  out = out.replace(/<font\b[^>]*>/gi, () => {
    depth++;
    return "<span>";
  });
  out = out.replace(/<\/font\s*>/gi, () => {
    if (depth > 0) {
      depth--;
      return "</span>";
    }
    return "";
  });
  out = out.replace(/<(?!\/?(?:span|br|b|i)\b)[^>]*>/gi, "");
  while (depth-- > 0) out += "</span>";
  return out;
}

const ENTRY_LABELS: Record<string, string> = {
  penetrate: "穿透", limitCycle: "射速", power: "威力", accurate: "精准", stability: "稳定", weight: "便携",
  bullet: "弹匣", bulletMax: "弹匣上限", scope: "瞄准", commentName: "名称", name: "名称", des: "说明",
  desc: "描述", comment: "说明", description: "描述", specific: "特性", codeName: "代号", weaponName: "所属武器",
  type: "类型", subType: "子类型", weaponType: "武器类型", quality: "品质", durability: "耐久", tags: "标签",
  weaponGroup: "武器组", sortLv: "排序", lv: "等级", position: "槽位", accessoryId: "配件ID", masteryRate: "熟练",
  limit: "上限", res: "资源", param: "参数", paramClient: "参数", logic: "逻辑", bonus: "加成", effectType: "效果类型",
  xlv: "强化等级", isUnique: "唯一", minValue: "最小值", maxValue: "最大值", unitValue: "单位值", precision: "精度",
  formulas: "公式", timeLimit: "时限", weaponSubType: "武器子类", maxReform: "最大重构", skillId: "技能ID", nextLevel: "下一级",
  basic_hp: "生命(猜)", basic_mp: "内力(猜)", basic_speed: "速度(猜)", basic_spRecover: "内力恢复(猜)",
  basic_sa: "强攻(猜)", basic_ext: "体能(猜)", basic_wis: "智力(猜)", basic_con: "体质(猜)", basic_ski: "技巧(猜)",
  basic_acRate: "命中率(猜)", basic_mcRate: "暴击率(猜)", basic_ac: "命中(猜)", basic_mc: "暴击(猜)",
  basic_def: "防御(猜)", basic_dodge: "闪避(猜)", basic_crit: "暴击(猜)", basic_atk: "攻击(猜)",
  damage: "伤害(猜)", attack: "攻击(猜)", defense: "防御(猜)", critical: "暴击(猜)", dodge: "闪避(猜)",
  hp: "生命(猜)", mp: "内力(猜)", speed: "速度(猜)", icon: "图标", sort: "排序", price: "价格", gold: "金币", coin: "点券",
};

type Item =
  | { type: "weapon"; id: number; name: string; codeName?: string; typeLabel?: string }
  | { type: "entry"; catLabel: string; name: string; detail?: string; payload?: string };

interface WeaponSync {
  weapons?: number;
  weaponUpdatedAt?: number;
  entries?: number;
  entryUpdatedAt?: number;
  updatedAt?: number;
  schedule?: string;
}

function EntryDetail({ payload }: { payload?: string }) {
  let obj: Record<string, any>;
  try {
    obj = JSON.parse(payload || "{}");
  } catch {
    return <div style={{ color: "var(--muted)" }}>无完整数据</div>;
  }
  const keys = Object.keys(obj);
  if (!keys.length) return <div style={{ color: "var(--muted)" }}>无字段</div>;
  const title = obj.commentName || obj.name || obj.weaponName || "";
  return (
    <div style={{ padding: "4px 2px" }}>
      {title && (
        <div
          style={{ fontSize: 18, fontWeight: 700, marginBottom: 14 }}
          dangerouslySetInnerHTML={{ __html: gameHtml(String(title)) }}
        />
      )}
      <table style={{ borderCollapse: "collapse", fontSize: 14, width: "100%", tableLayout: "fixed" }}>
        <tbody>
          {keys.map((k) => {
            let v = obj[k];
            if (v !== null && typeof v === "object") v = JSON.stringify(v, null, 2);
            return (
              <tr key={k}>
                <td style={{ fontWeight: 600, padding: "8px 20px 8px 0", verticalAlign: "top", width: "38%", wordBreak: "break-word" }}>
                  {ENTRY_LABELS[k] || k}{" "}
                  <span style={{ color: "var(--muted)", fontSize: 11, fontWeight: 400 }}>{k}</span>
                </td>
                <td
                  style={{ padding: "8px 0", whiteSpace: "pre-wrap", wordBreak: "break-word", lineHeight: 1.6 }}
                  dangerouslySetInnerHTML={{ __html: gameHtml(String(v)) }}
                />
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function WeaponDetail({ id }: { id: number }) {
  const [w, setW] = useState<any>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    setW(null);
    setErr("");
    api(`/api/public/weapon/${id}`)
      .then(setW)
      .catch((e) => setErr(e.message));
  }, [id]);
  if (err) return <div style={{ color: "var(--muted)" }}>加载失败：{err}</div>;
  if (!w) return <div style={{ color: "var(--muted)" }}>加载中…</div>;
  const panels: [string, any][] = [
    ["穿透", w.penetrate], ["射速", w.fireRate], ["威力", w.power], ["精准", w.accurate],
    ["稳定", w.stability], ["便携", w.weight], ["弹匣", w.bullet], ["弹匣上限", w.bulletMax],
  ];
  const bars = panels.filter((p) => p[1] != null);
  return (
    <div>
      <div style={{ fontSize: 20, fontWeight: 700, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        {w.name}
        {w.typeLabel && (
          <span className="badge" style={{ background: "var(--hover)", color: "var(--muted)" }}>
            {w.typeLabel}
          </span>
        )}
        <span style={{ color: "var(--muted)", fontSize: 13, fontWeight: 400 }}>{w.codeName || ""}</span>
      </div>
      {w.specific && (
        <div
          style={{ marginTop: 10, color: "var(--warn)", fontSize: 14 }}
          dangerouslySetInnerHTML={{ __html: gameHtml(w.specific) }}
        />
      )}
      {w.description && (
        <div style={{ marginTop: 10, lineHeight: 1.7 }} dangerouslySetInnerHTML={{ __html: gameHtml(w.description) }} />
      )}
      {bars.length ? (
        <div style={{ marginTop: 14, maxWidth: 440 }}>
          {bars.map(([label, v]) => {
            const n = Number(v);
            const pct = Math.max(0, Math.min(100, n));
            return (
              <div className="panel-row" key={label}>
                <span className="plabel">{label}</span>
                <span className="panel-bar">
                  <i style={{ width: `${pct}%` }} />
                </span>
                <span className="pval">{Math.round(n * 10) / 10}</span>
              </div>
            );
          })}
        </div>
      ) : (
        <div style={{ color: "var(--muted)", marginTop: 12 }}>该武器暂无面板数据</div>
      )}
    </div>
  );
}

export default function WeaponPage() {
  const router = useRouter();
  const params = useSearchParams();
  const [name, setName] = useState(params.get("q") || "");
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<{ items: Item[]; total: number; size: number; sync?: WeaponSync } | null>(null);
  const [sync, setSync] = useState<WeaponSync | null>(null);
  const [status, setStatus] = useState("");
  const [detail, setDetail] = useState<{ kind: "weapon"; id: number } | { kind: "entry"; payload?: string } | null>(null);

  const search = useCallback((q: string, p: number) => {
    if (!q.trim()) return;
    setStatus("搜索中…");
    setDetail(null);
    api(`/api/public/weapon/search?q=${encodeURIComponent(q.trim())}&page=${p}`)
      .then((d) => {
        setResult(d);
        if (d.sync) setSync(d.sync);
        setStatus(d.total ? "" : `没有匹配「${q.trim()}」的武器 / 技能 / 配件`);
      })
      .catch((e) => setStatus(`搜索失败：${e.message}`));
  }, []);

  useEffect(() => {
    api("/api/public/weapon/status")
      .then(setSync)
      .catch(() => {});
    const q = params.get("q");
    if (q) {
      setName(q);
      search(q, 1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const trigger = () => {
    if (!name.trim()) return;
    router.replace(`/weapon?q=${encodeURIComponent(name.trim())}`, { scroll: false });
    setPage(1);
    search(name, 1);
  };

  const totalPages = result ? Math.max(1, Math.ceil((result.total || 0) / (result.size || 100))) : 1;

  // 渲染：按类别分组
  let lastCat: string | null = null;
  return (
    <div className="fade-up">
      <div className="card glow-card" style={{ padding: "18px 20px", marginBottom: 14 }}>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "flex-end" }}>
          <div style={{ flex: "1 1 260px" }}>
            <label style={{ fontSize: 13, color: "var(--muted)", display: "block", marginBottom: 4 }}>
              武器名 / 代号
            </label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && trigger()}
              placeholder="如 M4A1 / 姜维之手"
              style={{ width: "100%" }}
            />
          </div>
          <button className="btn-primary" onClick={trigger}>
            🔍 搜索
          </button>
        </div>
        <div style={{ color: "var(--muted)", fontSize: 13, marginTop: 12, display: "flex", gap: 14, flexWrap: "wrap" }}>
          <span>武器数据：{sync?.weaponUpdatedAt ? fmtFullTime(sync.weaponUpdatedAt) : "—"}</span>
          <span>辅助数据：{sync?.entryUpdatedAt ? fmtFullTime(sync.entryUpdatedAt) : "—"}</span>
          <span>
            收录：{fmtNum(sync?.weapons || 0)} 把武器 / {fmtNum(sync?.entries || 0)} 条技能配件词缀
          </span>
          <span>{sync?.schedule || "每日 18:10（北京时间）自动同步"}</span>
        </div>
      </div>

      {status && (
        <div className="card card-pad" style={{ color: "var(--muted)", marginBottom: 14 }}>
          {status}
        </div>
      )}

      {result && result.total > 0 && (
        <div className="card" style={{ marginBottom: 14, overflow: "hidden" }}>
          {result.items.map((it, idx) => {
            const catLabel = it.type === "weapon" ? "武器" : it.catLabel;
            const showCat = catLabel !== lastCat;
            lastCat = catLabel;
            return (
              <div key={idx}>
                {showCat && (
                  <div
                    style={{
                      padding: "9px 16px",
                      fontSize: 12.5,
                      fontWeight: 700,
                      color: "var(--primary-strong)",
                      background: "var(--thead-bg)",
                      borderBottom: "1px solid var(--border)",
                    }}
                  >
                    {catLabel}
                  </div>
                )}
                {it.type === "weapon" ? (
                  <div
                    onClick={() => setDetail({ kind: "weapon", id: it.id })}
                    style={{
                      display: "flex",
                      gap: 10,
                      alignItems: "center",
                      padding: "11px 16px",
                      borderBottom: "1px solid var(--border)",
                      cursor: "pointer",
                    }}
                    className="hoverable"
                  >
                    <b>{it.name}</b>
                    <span style={{ color: "var(--muted)", fontSize: 13 }}>{it.codeName || ""}</span>
                    <span className="badge" style={{ marginLeft: "auto", background: "var(--hover)", color: "var(--muted)" }}>
                      {it.typeLabel || ""}
                    </span>
                  </div>
                ) : (
                  <div
                    onClick={() => setDetail({ kind: "entry", payload: it.payload })}
                    style={{ padding: "11px 16px", borderBottom: "1px solid var(--border)", cursor: "pointer" }}
                  >
                    <div dangerouslySetInnerHTML={{ __html: gameHtml(it.name) }} />
                    {it.detail && (
                      <div
                        style={{ color: "var(--muted)", fontSize: 13, marginTop: 3, lineHeight: 1.6 }}
                        dangerouslySetInnerHTML={{ __html: gameHtml(it.detail) }}
                      />
                    )}
                    <div style={{ color: "var(--muted)", fontSize: 12, marginTop: 3 }}>点击看全部字段 ›</div>
                  </div>
                )}
              </div>
            );
          })}
          <div style={{ padding: "4px 14px" }}>
            <Pager
              page={page}
              totalPages={totalPages}
              total={result.total}
              onPage={(p) => {
                setPage(p);
                search(name, p);
              }}
            />
          </div>
        </div>
      )}

      {detail && (
        <div className="card" style={{ padding: "20px 24px" }}>
          {detail.kind === "weapon" ? <WeaponDetail id={detail.id} /> : <EntryDetail payload={detail.payload} />}
        </div>
      )}
    </div>
  );
}
