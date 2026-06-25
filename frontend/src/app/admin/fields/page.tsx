"use client";

/** 字段配置：六大类榜的列显隐/显示名/渲染/列序（拖拽）+ 排名依据多条件排序（移植自旧版 admin-fields.html，改动后点「保存」一次性提交）。 */

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { adminApi, adminPost } from "@/lib/api";
import { AdminShell, useToast } from "@/components/AdminShell";
import { fmtNum } from "@/lib/format";
import { SERVER_NAME } from "@/lib/servers";
import { RealmTag, VipTag, DistrictTag } from "@/components/cells";

/* ── 六大类（global=跨服，代表 code 用 :all；同服用 :1 模板，配置对该榜所有区服生效） ── */
const GROUPS: { key: string; name: string; global?: boolean }[] = [
  { key: "REDUCED:382", name: "战力榜·同服" },
  { key: "REDUCED:331", name: "经验榜·同服" },
  { key: "REDUCED:333", name: "战力榜·跨服", global: true },
  { key: "REDUCED:332", name: "经验榜·跨服", global: true },
  { key: "LADDER:23", name: "天梯榜", global: true },
  { key: "TEAM", name: "战队榜" },
];

const RENDER_CN: Record<string, string> = {
  TEXT: "文本",
  NUMBER: "数字",
  REALM: "境界",
  VIP: "VIP",
  DISTRICT: "区",
  LONGTEXT: "长文本",
};
const RENDER_OPTS = ["TEXT", "NUMBER", "REALM", "VIP", "DISTRICT", "LONGTEXT"];

const REALM_KEYS = new Set(["0", "1", "2"]);

function repCode(g: { key: string; global?: boolean }): string {
  return g.key + (g.global ? ":all" : ":1");
}

interface FieldCfg {
  field: string;
  label: string;
  visible: boolean;
  render: string;
  order: number;
}

interface SortCond {
  field: string;
  dir: string;
}

/** 该示例值能否按此渲染类型有意义地展示（与旧版 Admin.renderCheck 等价）。空值视为可渲染。 */
function renderCheck(render: string, v: any): boolean {
  if (v === null || v === undefined || v === "") return true;
  if (render === "TEXT") return true;
  if (render === "NUMBER") return !isNaN(Number(v)) && isFinite(Number(v));
  if (render === "REALM") return REALM_KEYS.has(String(Number(v)));
  if (render === "VIP") {
    const n = Number(v);
    return !isNaN(n) && Number.isInteger(n) && n >= 0 && n <= 30;
  }
  if (render === "DISTRICT") return Number(v) in SERVER_NAME;
  return true;
}

/** 按渲染类型预览示例值（与旧版 Admin.renderPreview 等价，复用前台单元格组件、暗色主题）。 */
function Preview({ render, v }: { render: string; v: any }): ReactNode {
  if (v === null || v === undefined || v === "") return <span style={{ color: "var(--muted)" }}>—</span>;
  if (render === "NUMBER") return <b>{fmtNum(v)}</b>;
  if (render === "REALM") return <RealmTag v={v} />;
  if (render === "VIP") return <VipTag v={v} />;
  if (render === "DISTRICT") return <DistrictTag v={v} />;
  if (render === "LONGTEXT") {
    const sv = String(v);
    return <>{sv.length > 10 ? `${sv.slice(0, 10)}…` : sv}</>;
  }
  return <>{String(v)}</>;
}

export default function AdminFieldsPage() {
  const [toastNode, toast] = useToast();
  const [cur, setCur] = useState(GROUPS[0]);
  const [fields, setFields] = useState<FieldCfg[]>([]);
  const [sample, setSample] = useState<Record<string, any>>({});
  const [sorts, setSorts] = useState<SortCond[]>([]);
  const [loading, setLoading] = useState(true);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const dragField = useRef<string | null>(null);
  const [overField, setOverField] = useState<string | null>(null);

  const isTeam = cur.key === "TEAM";
  /** 排序条件可选字段（除 rank 外的全部字段）。 */
  const sortFields = fields.filter((f) => f.field !== "rank").map((f) => ({ field: f.field, label: f.label || f.field }));

  const apiErr = useCallback(
    (prefix: string) => (e: any) => {
      if (e.message !== "未登录") toast(`${prefix}：${e.message}`, false);
    },
    [toast]
  );

  /* ── 切换榜：加载字段配置 + 示例值 ── */
  const selectGroup = useCallback(
    (g: { key: string; name: string; global?: boolean }) => {
      setCur(g);
      setLoading(true);
      setDirty(false);
      setFields([]);
      Promise.all([
        adminApi(`/api/boards/fields?code=${encodeURIComponent(repCode(g))}`),
        adminApi(`/api/boards/sample?code=${encodeURIComponent(repCode(g))}`).catch(() => ({})),
      ])
        .then(([fd, sp]) => {
          const fs: FieldCfg[] = fd.fields || [];
          setFields(fs);
          setSample(sp || {});
          // 排名依据：无配置时默认第一个非 rank 字段降序
          const ss: SortCond[] = fd.sorts || [];
          if (!ss.length) {
            const first = fs.find((f) => f.field !== "rank");
            setSorts(first ? [{ field: first.field, dir: "DESC" }] : []);
          } else {
            setSorts(ss);
          }
          setLoading(false);
        })
        .catch((e) => {
          setLoading(false);
          apiErr("加载失败")(e);
        });
    },
    [apiErr]
  );

  useEffect(() => {
    selectGroup(GROUPS[0]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /* ── 改动只更新本地草稿，点「保存」才提交（不再即时自动保存）── */
  const patchField = (field: string, patch: Partial<FieldCfg>) => {
    setDirty(true);
    setFields((prev) => prev.map((f) => (f.field === field ? { ...f, ...patch } : f)));
  };

  /* ── 渲染类型变更：示例值无法渲染则回退文本并提示（与旧版一致）── */
  const changeRender = (field: string, render: string) => {
    const v = sample[field];
    let r = render;
    if (!renderCheck(render, v)) {
      toast(`该字段示例「${v == null ? "空" : v}」无法按「${RENDER_CN[render] || render}」渲染，已回退文本`, false);
      r = "TEXT";
    }
    patchField(field, { render: r });
  };

  /* ── 拖拽排序：插到目标行前面（仅本地，点「保存」才提交 order）── */
  const onDrop = (targetField: string) => {
    const src = dragField.current;
    dragField.current = null;
    setOverField(null);
    if (!src || src === targetField || targetField === "rank") return;
    setFields((prev) => {
      const next = prev.filter((f) => f.field !== src);
      const dragged = prev.find((f) => f.field === src);
      if (!dragged) return prev;
      const idx = next.findIndex((f) => f.field === targetField);
      next.splice(idx, 0, dragged);
      return next;
    });
    setDirty(true);
  };

  const patchSort = (i: number, patch: Partial<SortCond>) => {
    setDirty(true);
    setSorts((prev) => prev.map((c, j) => (j === i ? { ...c, ...patch } : c)));
  };

  const delSort = (i: number) => {
    if (sorts.length <= 1) {
      toast("至少保留一个排序条件", false);
      return;
    }
    setDirty(true);
    setSorts((prev) => prev.filter((_, j) => j !== i));
  };

  const addSort = () => {
    if (!sortFields.length) return;
    setDirty(true);
    setSorts((prev) => [...prev, { field: sortFields[0].field, dir: "DESC" }]);
  };

  /* ── 一键保存：把所有列（显隐/显示名/渲染/列序）+ 排名依据一起提交。 */
  const saveAll = async () => {
    setSaving(true);
    try {
      const code = repCode(cur);
      // 列配置：按当前顺序逐列提交（order=索引；rank 列不带 render）。
      let idx = 0;
      for (const f of fields) {
        let qs = `?code=${encodeURIComponent(code)}&field=${encodeURIComponent(f.field)}`;
        qs += `&label=${encodeURIComponent(f.label || "")}&visible=${f.visible}&order=${idx}`;
        if (f.field !== "rank") qs += `&render=${encodeURIComponent(f.render)}`;
        await adminPost(`/api/boards/field${qs}`);
        idx++;
      }
      // 排名依据（战队榜为特例，无排序配置）。
      if (!isTeam && sorts.length) {
        const spec = sorts.map((c) => `${c.field}:${c.dir}`).join(",");
        await adminPost(`/api/boards/sort?code=${encodeURIComponent(code)}&spec=${encodeURIComponent(spec)}`);
      }
      setDirty(false);
      toast("已保存");
    } catch (e: any) {
      apiErr("保存失败")(e);
    } finally {
      setSaving(false);
    }
  };

  /* ── 切换榜前若有未保存改动，先确认丢弃。 */
  const switchGroup = (g: { key: string; name: string; global?: boolean }) => {
    if (g.key === cur.key) return;
    if (dirty && !confirm("有未保存的改动，切换将丢弃。确定切换？")) return;
    selectGroup(g);
  };

  return (
    <AdminShell title="🧩 字段配置">
      <div className="card card-pad fade-up">
        <div style={{ color: "var(--muted)", fontSize: 13, margin: "0 0 16px", lineHeight: 1.7 }}>
          勾选「显示」该列才在前台出现；<b style={{ color: "var(--text)" }}>名次列固定第一</b>不可改。
          <b style={{ color: "var(--text)" }}>拖动行</b>调列序（越上越靠左）。
          <b style={{ color: "var(--text)" }}>排名依据</b>选字段+方向，名次=排序后行号。
          渲染任选，选后若示例值渲染失败会自动回退文本并提示。改动后需点右上角
          <b style={{ color: "var(--text)" }}>「💾 保存」</b>才生效。境界=I/II/III，VIP=0~6/7~10/11~17，区=区号转区服名。
          每行带「示例」(按排名首行) 与「预览」。
        </div>

        {/* 六类榜切换 + 保存 */}
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 14, alignItems: "center" }}>
          {GROUPS.map((g) => (
            <button
              key={g.key}
              className={cur.key === g.key ? "btn-primary" : ""}
              style={{ borderRadius: 999, padding: "8px 16px" }}
              onClick={() => switchGroup(g)}
            >
              {g.name}
            </button>
          ))}
          <span style={{ marginLeft: "auto", display: "inline-flex", gap: 10, alignItems: "center" }}>
            {dirty && <span style={{ color: "var(--warn)", fontSize: 13 }}>● 有未保存改动</span>}
            <button className="btn-primary" onClick={saveAll} disabled={saving || !dirty} style={{ minWidth: 96 }}>
              {saving ? <span className="spinner" /> : "💾 保存"}
            </button>
          </span>
        </div>

        {/* 提示 + 排名依据 */}
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 12, flexWrap: "wrap" }}>
          <span style={{ color: "var(--muted)", fontSize: 13 }}>{cur.name}（配置对该榜所有区服生效）</span>
          <span style={{ marginLeft: "auto" }} />
          {isTeam ? (
            <span style={{ color: "var(--muted)", fontSize: 13 }}>
              战队榜排序为特例：前台可切换「按普通(经验) / 按冒险(贡献)」，无需在此配置
            </span>
          ) : (
            <>
              <label style={{ color: "var(--muted)", fontSize: 13 }}>排名依据</label>
              <span style={{ display: "inline-flex", gap: 6, flexWrap: "wrap", alignItems: "center" }}>
                {sorts.map((s, i) => (
                  <span
                    key={i}
                    style={{
                      display: "inline-flex",
                      gap: 3,
                      alignItems: "center",
                      border: "1px solid var(--border)",
                      borderRadius: 8,
                      padding: "2px 4px",
                    }}
                  >
                    <select value={s.field} onChange={(e) => patchSort(i, { field: e.target.value })} style={{ padding: "4px 6px" }}>
                      {sortFields.map((f) => (
                        <option key={f.field} value={f.field}>
                          {f.label}
                        </option>
                      ))}
                    </select>
                    <select value={s.dir === "ASC" ? "ASC" : "DESC"} onChange={(e) => patchSort(i, { dir: e.target.value })} style={{ padding: "4px 6px" }}>
                      <option value="DESC">↓高到低</option>
                      <option value="ASC">↑低到高</option>
                    </select>
                    <a
                      title="删除此条件"
                      style={{ cursor: "pointer", color: "var(--muted)", padding: "0 4px", fontSize: 16, lineHeight: 1 }}
                      onClick={() => delSort(i)}
                    >
                      ×
                    </a>
                  </span>
                ))}
              </span>
              <button onClick={addSort} style={{ padding: "6px 10px", fontSize: 13 }}>
                + 条件
              </button>
            </>
          )}
        </div>

        {loading ? (
          <div style={{ padding: 60, textAlign: "center" }}>
            <span className="spinner" />
          </div>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th style={{ width: 36 }}></th>
                  <th style={{ width: 60 }}>显示</th>
                  <th>字段</th>
                  <th>显示名</th>
                  <th>示例</th>
                  <th style={{ width: 120 }}>渲染</th>
                  <th>预览</th>
                </tr>
              </thead>
              <tbody>
                {fields.map((f) => {
                  const isRank = f.field === "rank";
                  const eg = sample[f.field];
                  return (
                    <tr
                      key={f.field}
                      draggable={!isRank}
                      onDragStart={() => {
                        if (!isRank) dragField.current = f.field;
                      }}
                      onDragOver={(e) => {
                        e.preventDefault();
                        setOverField(f.field);
                      }}
                      onDragLeave={() => setOverField((p) => (p === f.field ? null : p))}
                      onDrop={(e) => {
                        e.preventDefault();
                        onDrop(f.field);
                      }}
                      style={{
                        cursor: isRank ? undefined : "grab",
                        background: isRank ? "var(--hover)" : undefined,
                        borderTop: overField === f.field ? "2px solid var(--primary)" : undefined,
                      }}
                    >
                      <td>
                        <span style={{ color: "var(--muted)", cursor: isRank ? "default" : "grab", userSelect: "none" }}>
                          {isRank ? "🔒" : "≡"}
                        </span>
                      </td>
                      <td>
                        <input
                          type="checkbox"
                          checked={!!f.visible}
                          disabled={isRank}
                          onChange={(e) => patchField(f.field, { visible: e.target.checked })}
                        />
                      </td>
                      <td>
                        <span style={{ color: "var(--muted)", fontSize: 12 }}>{f.field}</span>
                      </td>
                      <td>
                        <input
                          value={f.label || ""}
                          onChange={(e) => patchField(f.field, { label: e.target.value })}
                          style={{ width: 130, padding: "5px 8px" }}
                        />
                      </td>
                      <td
                        title={eg == null ? "" : String(eg)}
                        style={{ color: "var(--muted)", maxWidth: 160, overflow: "hidden", textOverflow: "ellipsis" }}
                      >
                        {eg == null ? <span style={{ color: "var(--border-strong)" }}>—</span> : String(eg)}
                      </td>
                      <td>
                        {isRank ? (
                          <span style={{ color: "var(--muted)" }}>—</span>
                        ) : (
                          <select value={f.render} onChange={(e) => changeRender(f.field, e.target.value)} style={{ padding: "5px 8px" }}>
                            {RENDER_OPTS.map((r) => (
                              <option key={r} value={r}>
                                {RENDER_CN[r]}
                              </option>
                            ))}
                          </select>
                        )}
                      </td>
                      <td>
                        <Preview render={isRank ? "TEXT" : f.render} v={eg} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {toastNode}
    </AdminShell>
  );
}
