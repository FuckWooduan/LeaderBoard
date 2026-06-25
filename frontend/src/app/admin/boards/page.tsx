"use client";

/** 榜单管理：走马灯广告 + 分区排行榜 + 六大类主榜（移植自旧版 admin-boards.html，功能逐项等价）。 */

import { useCallback, useEffect, useRef, useState, type ChangeEvent } from "react";
import { adminApi, adminPost } from "@/lib/api";
import { AdminShell, useToast } from "@/components/AdminShell";
import { fmtNum } from "@/lib/format";

/* ── 六大类（顺序、前缀 → 名称；global=跨服单榜） ── */
const CATS: { key: string; name: string; global: boolean }[] = [
  { key: "REDUCED:382", name: "战力榜·同服", global: false },
  { key: "REDUCED:331", name: "经验榜·同服", global: false },
  { key: "REDUCED:333", name: "战力榜·跨服", global: true },
  { key: "REDUCED:332", name: "经验榜·跨服", global: true },
  { key: "LADDER:23", name: "天梯榜", global: true },
  { key: "TEAM", name: "战队榜", global: false },
];

/* ── 区服名：counts 返回的 server 是 loginId（登录号），与旧版 LOGIN_NAME 一致；
      注意 lib/servers 的 SERVER_NAME key 是 districtId，不适用于这里。 ── */
const LOGIN_NAME: Record<string, string> = {
  15: "电信八区", 13: "电信七区", 11: "电信六区", 9: "电信五区", 7: "电信四区", 6: "电信三区", 4: "电信二区", 2: "电信一区",
  16: "联通六区", 14: "联通五区", 10: "联通四区", 8: "联通三区", 5: "联通二区", 3: "联通一区",
  21: "双线七区", 19: "双线五区", 18: "双线四区", 17: "双线三区", 12: "双线二区", 1: "双线一区",
};
function svName(s: string): string {
  return s === "all" ? "全服" : LOGIN_NAME[s] || `${s}区`;
}
function groupKey(code: string): string {
  const i = code.lastIndexOf(":");
  return i < 0 ? code : code.substring(0, i);
}

interface CountRow {
  code: string;
  server: string;
  rows: number;
  enabled: boolean;
  publicVisible: boolean;
}

interface SliceKey {
  rankKey: string;
  name?: string;
  label?: string;
  categories?: string[];
  sources?: number;
  dynamic?: boolean;
  visible?: boolean;
  hidden?: boolean;
  prevSeason?: string;
  brawl?: boolean;
  sameZone?: string;
  partitionCount?: number;
  seasonEndAt?: number;
}
const SLICE_CATEGORY_PRESETS = ["异能榜", "终极榜", "副本榜", "乱斗榜", "彩金币榜", "深空榜"];

/* ════════ A. 顶部走马灯广告 ════════ */
function MarqueeCard({ toast }: { toast: (msg: string, ok?: boolean) => void }) {
  const [enabled, setEnabled] = useState(false);
  const [lines, setLines] = useState("");
  const [image, setImage] = useState("");
  const [url, setUrl] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    adminApi("/api/boards/marquee")
      .then((d) => {
        setEnabled(!!d.enabled);
        setLines(d.lines || "");
        const img = d.image || "";
        setImage(img);
        if (img && !img.startsWith("data:")) setUrl(img);
      })
      .catch(() => {});
  }, []);

  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (f.size > 3.5 * 1024 * 1024) {
      toast("图片过大，请压缩到 3.5MB 内或改用链接", false);
      return;
    }
    const r = new FileReader();
    r.onload = () => {
      setImage(String(r.result));
      setUrl("");
    };
    r.readAsDataURL(f);
  };

  const clearImg = () => {
    setImage("");
    setUrl("");
    if (fileRef.current) fileRef.current.value = "";
  };

  const save = () => {
    adminPost("/api/boards/marquee", { enabled, lines, image })
      .then(() => toast("已保存"))
      .catch((e) => {
        if (e.message !== "未登录") toast(`保存失败：${e.message}`, false);
      });
  };

  return (
    <div className="card card-pad fade-up" style={{ marginBottom: 16 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 12, flexWrap: "wrap" }}>
        <b style={{ fontSize: 16 }}>📢 顶部走马灯广告</b>
        <span style={{ color: "var(--muted)", fontSize: 13 }}>横向滚动 · 支持多行 · 末尾图片可点击放大</span>
        <label style={{ marginLeft: "auto", fontSize: 14, display: "flex", alignItems: "center", gap: 6, cursor: "pointer" }}>
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> 启用
        </label>
      </div>
      <textarea
        rows={4}
        value={lines}
        onChange={(e) => setLines(e.target.value)}
        placeholder="每行一条公告，支持多行（前台横向滚动依次展示）"
        style={{ width: "100%", resize: "vertical" }}
      />
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 12, flexWrap: "wrap" }}>
        <label className="btn" style={{ display: "inline-flex", alignItems: "center", cursor: "pointer" }}>
          选择图片
          <input ref={fileRef} type="file" accept="image/*" onChange={onFile} style={{ display: "none" }} />
        </label>
        <input
          value={url}
          onChange={(e) => {
            const v = e.target.value.trim();
            setUrl(e.target.value);
            setImage(v);
          }}
          placeholder="或粘贴图片链接 https://…"
          style={{ flex: 1, minWidth: 200 }}
        />
        <button onClick={clearImg}>清除图片</button>
      </div>
      <div style={{ marginTop: 10 }}>
        {image ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={image} alt="预览" style={{ maxHeight: 80, borderRadius: 6, border: "1px solid var(--border)" }} />
        ) : (
          <span style={{ color: "var(--muted)", fontSize: 13 }}>未设置图片</span>
        )}
      </div>
      <div style={{ marginTop: 14 }}>
        <button className="btn-primary" onClick={save}>
          保存
        </button>
      </div>
    </div>
  );
}

/* ════════ B. 分区排行榜管理 ════════ */
function SliceCard({ toast }: { toast: (msg: string, ok?: boolean) => void }) {
  const [keys, setKeys] = useState<SliceKey[] | null>(null);
  const [cov, setCov] = useState<Record<string, number>>({});
  const [labels, setLabels] = useState<Record<string, string>>({});
  const [categoryText, setCategoryText] = useState<Record<string, string>>({});
  const [fetching, setFetching] = useState<Record<string, boolean>>({});
  const [seasonMsg, setSeasonMsg] = useState("");
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [saving, setSaving] = useState(false);
  const seasonTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  // 保存失败要明确报错（旧版 saveErr：401 已由 adminApi 跳登录）。
  const saveErr = useCallback(
    (e: any) => {
      if (e && e.message !== "未登录") toast(`保存失败，请重新登录后台再试：${e.message || ""}`, false);
    },
    [toast]
  );

  /** 刷新某 rankKey 的「覆盖分区 x/200」。 */
  const refreshCov = useCallback((rk: string) => {
    adminApi(`/api/public/slice/grid?rankKey=${encodeURIComponent(rk)}`)
      .then((g) => {
        const n = ((g.coverage || []) as number[]).filter((c) => c > 0).length;
        setCov((prev) => ({ ...prev, [rk]: n }));
      })
      .catch(() => {});
  }, []);

  const load = useCallback(() => {
    adminApi("/api/public/slice/keys")
      .then((list: SliceKey[]) => {
        const ks = list || [];
        setKeys(ks);
        const lb: Record<string, string> = {};
        const ct: Record<string, string> = {};
        ks.forEach((k) => {
          lb[k.rankKey] = k.label || "";
          ct[k.rankKey] = (k.categories || []).join("，");
        });
        setLabels(lb);
        setCategoryText(ct);
        setDirty(new Set());
        ks.forEach((k) => refreshCov(k.rankKey));
      })
      .catch(() => {});
  }, [refreshCov]);

  useEffect(() => {
    load();
    return () => {
      if (seasonTimer.current) clearInterval(seasonTimer.current);
    };
  }, [load]);

  const parseCategories = (text: string): string[] =>
    Array.from(
      new Set(
        (text || "")
          .split(/[，,\s]+/)
          .map((x) => x.trim())
          .filter(Boolean)
      )
    );

  const toLocalInput = (ms?: number): string => {
    if (!ms) return "";
    const d = new Date(ms);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };

  /* ── 以下改动只更新本地草稿，点「保存更改」才一次性提交 ── */
  const markDirty = (rk: string) => setDirty((prev) => (prev.has(rk) ? prev : new Set(prev).add(rk)));

  const setLabel = (rk: string, label: string) => {
    setLabels((p) => ({ ...p, [rk]: label }));
    markDirty(rk);
  };

  const setFlag = (rk: string, field: keyof SliceKey, value: boolean | string) => {
    setKeys((prev) => prev && prev.map((k) => (k.rankKey === rk ? { ...k, [field]: value } : k)));
    markDirty(rk);
  };

  const setPartitionCount = (rk: string, value: number) => {
    setKeys((prev) => prev && prev.map((k) => (k.rankKey === rk ? { ...k, partitionCount: value } : k)));
    markDirty(rk);
  };

  const setSeasonEndAt = (rk: string, value: string) => {
    const ms = value ? new Date(value).getTime() : 0;
    const seasonEndAt = Number.isFinite(ms) ? ms : 0;
    setKeys((prev) => prev && prev.map((k) => (k.rankKey === rk ? { ...k, seasonEndAt } : k)));
    markDirty(rk);
  };

  const setCategoryDraft = (rk: string, text: string) => {
    setCategoryText((p) => ({ ...p, [rk]: text }));
    markDirty(rk);
  };

  /** 预设标签勾选：在 categoryText 文本中增删该标签。 */
  const togglePreset = (rk: string, cat: string, on: boolean) => {
    const cur = parseCategories(categoryText[rk] ?? "");
    const next = on ? Array.from(new Set([...cur, cat])) : cur.filter((x) => x !== cat);
    setCategoryDraft(rk, next.join("，"));
  };

  /** 乱斗榜开关：同时联动「乱斗榜」分类标签。 */
  const setBrawl = (rk: string, on: boolean) => {
    setKeys((prev) => prev && prev.map((k) => (k.rankKey === rk ? { ...k, brawl: on } : k)));
    togglePreset(rk, "乱斗榜", on); // 内含 markDirty
  };

  /* ── 一键保存：逐改动行把各配置字段提交到对应接口 ── */
  const saveAll = async () => {
    if (!dirty.size || !keys) return;
    setSaving(true);
    try {
      const dirtyKeys = keys.filter((k) => dirty.has(k.rankKey));
      for (const k of dirtyKeys) {
        const rk = k.rankKey;
        const pc = Math.max(1, Math.min(200, Number(k.partitionCount) || 200));
        await adminPost("/api/boards/slice/label", { rankKey: rk, label: (labels[rk] || "").trim() });
        await adminPost("/api/boards/slice/categories", { rankKey: rk, categories: parseCategories(categoryText[rk] ?? "") });
        await adminPost("/api/boards/slice/partition-count", { rankKey: rk, partitionCount: pc });
        await adminPost("/api/boards/slice/season-end-at", { rankKey: rk, seasonEndAt: k.seasonEndAt || 0 });
        await adminPost("/api/boards/slice/dynamic", { rankKey: rk, dynamic: !!k.dynamic });
        await adminPost("/api/boards/slice/visible", { rankKey: rk, visible: k.visible !== false });
        await adminPost("/api/boards/slice/hidden", { rankKey: rk, hidden: !!k.hidden });
        await adminPost("/api/boards/slice/brawl", { rankKey: rk, brawl: !!k.brawl });
        await adminPost("/api/boards/slice/prev-season", { rankKey: rk, prevSeason: k.prevSeason || "" });
        await adminPost("/api/boards/slice/same-zone", { rankKey: rk, sameZone: k.sameZone || "" });
        refreshCov(rk);
      }
      setDirty(new Set());
      toast(`已保存 ${dirtyKeys.length} 个榜的更改`);
    } catch (e) {
      saveErr(e);
    } finally {
      setSaving(false);
    }
  };

  const fetchRankKey = (rk: string) => {
    setFetching((p) => ({ ...p, [rk]: true }));
    adminPost(`/api/boards/slice/fetch-rankkey?rankKey=${encodeURIComponent(rk)}`)
      .then((d) => {
        toast(d.ok ? `已高并发抓取 ${d.partitions || 0} 个分区` : d.message || "抓取失败", !!d.ok);
        // 抓取异步进行（约 15-20 秒），分两次回刷该行覆盖数。
        setTimeout(() => refreshCov(rk), 8000);
        setTimeout(() => refreshCov(rk), 22000);
      })
      .catch((e) => {
        if (e.message !== "未登录") toast(`抓取失败：${e.message || ""}`, false);
      })
      .finally(() => setFetching((p) => ({ ...p, [rk]: false })));
  };

  const fetchBoards = () => {
    adminPost("/api/boards/slice/fetch-boards")
      .then((d) => toast(`已触发抓取 ${d.submitted || 0} 个`))
      .catch(saveErr);
  };

  const rediscover = () => {
    if (
      !confirm(
        "重新发现所有账号×区的 rankKey 覆盖？\n用于让新榜(如仲夏之梦)铺开到全部分区。后台会重新登录所有号探测，需较长时间，坏IP会被自动剔除。"
      )
    )
      return;
    adminPost("/api/boards/slice/refetch")
      .then((d) => {
        const n = d.enqueued || 0;
        toast(n > 0 ? `已提交 ${n} 个账号×区重新发现，覆盖会逐步上升` : "5 分钟内已触发过，请稍后再试", n > 0);
      })
      .catch(saveErr);
  };

  const reset = () => {
    if (!confirm("确定全量重抓所有分区榜？\n不清空，每个分区抓到后整段覆盖旧数据（无空窗）。命名/显示/动态等设置保留。")) return;
    adminPost("/api/boards/slice/reset")
      .then((d) => {
        toast(`已提交 ${d.resubmitted || 0} 个来源全量覆盖重抓`);
        setTimeout(load, 1500);
      })
      .catch((e) => {
        if (e.message !== "未登录") toast(`重抓失败：${e.message || ""}`, false);
      });
  };

  const seasonEnd = () => {
    if (
      !confirm(
        "确定立即「季末抢数」？\n将以最大并发(60)抓取所有隐藏榜全部分区，循环重试直到全齐。\n仅赛季结束当晚使用——平时大并发有封号/代理负载风险。"
      )
    )
      return;
    setSeasonMsg("季末抢数：启动中…");
    adminPost("/api/boards/slice/season-end")
      .then((d) => {
        toast(d.message || "已启动");
        if (d.ok) {
          if (seasonTimer.current) clearInterval(seasonTimer.current);
          seasonTimer.current = setInterval(() => {
            adminApi("/api/boards/slice/season-status")
              .then((s) => {
                const st = s.status || "";
                setSeasonMsg(`季末抢数：${st}`);
                if ((st.indexOf("done") === 0 || st.indexOf("error") === 0) && seasonTimer.current) {
                  clearInterval(seasonTimer.current);
                  seasonTimer.current = null;
                }
              })
              .catch(() => {
                if (seasonTimer.current) {
                  clearInterval(seasonTimer.current);
                  seasonTimer.current = null;
                }
              });
          }, 5000);
        } else {
          setSeasonMsg(`季末抢数：${d.status || ""}`);
        }
      })
      .catch((e) => {
        if (e.message !== "未登录") toast(`季末抢数失败：${e.message || ""}`, false);
      });
  };

  /** 上一赛季/同区榜下拉的公共选项（所有 rankKey）。 */
  const prevOptions = (
    <>
      <option value="">（无）</option>
      {(keys || []).map((x) => (
        <option key={x.rankKey} value={x.rankKey}>
          {x.name || x.rankKey}
        </option>
      ))}
    </>
  );

  return (
    <div className="card card-pad fade-up" style={{ marginBottom: 16 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 10, flexWrap: "wrap" }}>
        <b style={{ fontSize: 16 }}>🧩 分区排行榜</b>
        <span style={{ color: "var(--muted)", fontSize: 13 }}>
          给 rankKey 命名 · 静态(抓一次)/动态(每30分钟刷新) · 改动后点「💾 保存更改」生效
        </span>
        <span style={{ marginLeft: "auto", display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
          {dirty.size > 0 && <span style={{ color: "var(--warn)", fontSize: 13 }}>● {dirty.size} 个榜未保存</span>}
          <button className="btn-primary" onClick={saveAll} disabled={saving || dirty.size === 0} style={{ minWidth: 110 }}>
            {saving ? <span className="spinner" /> : "💾 保存更改"}
          </button>
          <button onClick={fetchBoards}>触发抓取</button>
          <button onClick={rediscover} title="重新登录所有账号×区探测 rankKey 覆盖；新榜(如仲夏之梦)铺开到全部分区用这个">
            🔄 重新发现(新榜铺开)
          </button>
          <button onClick={load}>刷新</button>
          <button onClick={reset}>
            全量覆盖重抓
          </button>
          <button
            className="btn-danger"
            onClick={seasonEnd}
            title="赛季结束抢数：最大并发抓所有隐藏榜全部分区，循环重试到全齐"
          >
            🏁 季末抢数
          </button>
          {seasonMsg && <span style={{ color: "var(--muted)", fontSize: 13 }}>{seasonMsg}</span>}
        </span>
      </div>
      <div className="table-wrap">
        <table className="data" style={{ minWidth: 1180 }}>
          <thead>
            <tr>
              <th>rankKey</th>
              <th>名称（可编辑）</th>
              <th>分类标签</th>
              <th className="num">来源号</th>
              <th className="num">覆盖分区</th>
              <th>分区数量</th>
              <th>结束时间</th>
              <th>动态刷新</th>
              <th>前台显示</th>
              <th>隐藏榜</th>
              <th>上一赛季</th>
              <th>乱斗榜</th>
              <th>同区榜</th>
              <th>一键全抓</th>
            </tr>
          </thead>
          <tbody>
            {keys === null ? (
              <tr>
                <td colSpan={12} style={{ color: "var(--muted)", padding: 16 }}>
                  加载中…
                </td>
              </tr>
            ) : !keys.length ? (
              <tr>
                <td colSpan={12} style={{ color: "var(--muted)", padding: 16 }}>
                  还没采集到 rankKey（后台分区获取进行中，稍后刷新）
                </td>
              </tr>
            ) : (
              keys.map((k) => (
                <tr key={k.rankKey}>
                  <td>{k.rankKey}</td>
                  <td>
                    <input
                      value={labels[k.rankKey] ?? ""}
                      placeholder={`${k.rankKey}（点此命名）`}
                      onChange={(e) => setLabel(k.rankKey, e.target.value)}
                      style={{ width: 180, padding: "5px 8px" }}
                    />
                  </td>
                  <td style={{ minWidth: 260 }}>
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginBottom: 6 }}>
                      {SLICE_CATEGORY_PRESETS.map((cat) => {
                        const checked = parseCategories(categoryText[k.rankKey] ?? "").includes(cat);
                        return (
                          <label key={cat} style={{ cursor: "pointer", userSelect: "none", fontSize: 13 }}>
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={(e) => togglePreset(k.rankKey, cat, e.target.checked)}
                            />{" "}
                            {cat}
                          </label>
                        );
                      })}
                    </div>
                    <input
                      value={categoryText[k.rankKey] ?? ""}
                      placeholder="自定义标签，用逗号或空格分隔"
                      onChange={(e) => setCategoryDraft(k.rankKey, e.target.value)}
                      style={{ width: "100%", padding: "5px 8px" }}
                    />
                  </td>
                  <td className="num">{fmtNum(k.sources)}</td>
                  <td className="num">{cov[k.rankKey] != null ? `${cov[k.rankKey]} / ${k.partitionCount || 200}` : "…"}</td>
                  <td>
                    <input
                      type="number"
                      min={1}
                      max={200}
                      value={k.partitionCount || 200}
                      onChange={(e) => setPartitionCount(k.rankKey, Number(e.target.value) || 200)}
                      style={{ width: 72, padding: "4px 6px" }}
                    />
                  </td>
                  <td>
                    <input
                      type="datetime-local"
                      value={toLocalInput(k.seasonEndAt)}
                      onChange={(e) => setSeasonEndAt(k.rankKey, e.target.value)}
                      style={{ width: 170, padding: "4px 6px" }}
                    />
                  </td>
                  <td>
                    <label style={{ cursor: "pointer", userSelect: "none" }}>
                      <input
                        type="checkbox"
                        checked={!!k.dynamic}
                        onChange={(e) => setFlag(k.rankKey, "dynamic", e.target.checked)}
                      />{" "}
                      动态
                    </label>
                  </td>
                  <td>
                    <label style={{ cursor: "pointer", userSelect: "none" }}>
                      <input
                        type="checkbox"
                        checked={k.visible !== false}
                        onChange={(e) => setFlag(k.rankKey, "visible", e.target.checked)}
                      />{" "}
                      显示
                    </label>
                  </td>
                  <td>
                    <label style={{ cursor: "pointer", userSelect: "none" }}>
                      <input
                        type="checkbox"
                        checked={!!k.hidden}
                        onChange={(e) => setFlag(k.rankKey, "hidden", e.target.checked)}
                      />{" "}
                      隐藏榜
                    </label>
                  </td>
                  <td>
                    <select
                      value={k.prevSeason || ""}
                      onChange={(e) => setFlag(k.rankKey, "prevSeason", e.target.value)}
                      style={{ padding: "4px 6px", maxWidth: 160 }}
                    >
                      {prevOptions}
                    </select>
                  </td>
                  <td>
                    <label style={{ cursor: "pointer", userSelect: "none" }}>
                      <input
                        type="checkbox"
                        checked={!!k.brawl}
                        onChange={(e) => setBrawl(k.rankKey, e.target.checked)}
                      />{" "}
                      乱斗榜
                    </label>
                  </td>
                  <td>
                    <select
                      value={k.sameZone || ""}
                      title="手动指定同分区的榜（候选源）；设了即覆盖自动判定"
                      onChange={(e) => setFlag(k.rankKey, "sameZone", e.target.value)}
                      style={{ padding: "4px 6px", maxWidth: 160 }}
                    >
                      {prevOptions}
                    </select>
                  </td>
                  <td>
                    <button
                      disabled={!!fetching[k.rankKey]}
                      onClick={() => fetchRankKey(k.rankKey)}
                      title="高并发(40)立即抓取本榜全部分区"
                      style={{
                        background: "var(--accent)",
                        color: "#0b1020",
                        border: "none",
                        fontWeight: 600,
                        whiteSpace: "nowrap",
                        padding: "6px 12px",
                      }}
                    >
                      {fetching[k.rankKey] ? "抓取中…" : "⚡一键全抓"}
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ════════ C. 六大类主榜 ════════ */
function CatsSection({ toast }: { toast: (msg: string, ok?: boolean) => void }) {
  const [all, setAll] = useState<CountRow[] | null>(null);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [saving, setSaving] = useState(false);

  const load = useCallback(() => {
    adminApi("/api/boards/counts")
      .then((d: CountRow[]) => {
        setAll(d || []);
        setDirty(new Set());
      })
      .catch((e) => {
        if (e.message !== "未登录") toast(`加载失败：${e.message}`, false);
      });
  }, [toast]);

  useEffect(() => {
    load();
  }, [load]);

  const setRow = (code: string, patch: Partial<CountRow>) =>
    setAll((prev) => prev && prev.map((b) => (b.code === code ? { ...b, ...patch } : b)));

  const markDirty = (code: string) => setDirty((prev) => (prev.has(code) ? prev : new Set(prev).add(code)));

  /* ── 抓取/开放开关只改本地草稿，点分类上的「保存」才提交 ── */
  const setEnabled = (code: string, v: boolean) => {
    setRow(code, { enabled: v });
    markDirty(code);
  };
  const setPublic = (code: string, v: boolean) => {
    setRow(code, { publicVisible: v });
    markDirty(code);
  };

  /* ── 保存某分类下所有改动行（抓取 + 开放）。 */
  const saveCat = async (codes: string[]) => {
    const targets = (all || []).filter((b) => codes.includes(b.code) && dirty.has(b.code));
    if (!targets.length) return;
    setSaving(true);
    try {
      for (const b of targets) {
        await adminPost(`/api/boards/enable?code=${encodeURIComponent(b.code)}&enabled=${!!b.enabled}`);
        await adminPost(`/api/boards/public?code=${encodeURIComponent(b.code)}&public=${!!b.publicVisible}`);
      }
      setDirty((prev) => {
        const n = new Set(prev);
        targets.forEach((b) => n.delete(b.code));
        return n;
      });
      toast(`已保存 ${targets.length} 行`);
    } catch (e: any) {
      if (e.message !== "未登录") toast(`保存失败：${e.message}`, false);
    } finally {
      setSaving(false);
    }
  };

  const refresh = (code: string) => {
    adminPost(`/api/boards/refresh?code=${encodeURIComponent(code)}`)
      .then(() => toast("已排入刷新"))
      .catch(() => {});
  };

  /** 分类批量：逐行调用，全部完成后 toast + 重载。 */
  const bulk = (codes: string[], fn: (c: string) => Promise<any>) => {
    if (!codes.length) {
      toast("无榜", false);
      return;
    }
    let done = 0;
    codes.forEach((c) => {
      fn(c)
        .then(() => {
          done++;
          if (done === codes.length) {
            toast(`完成 ${codes.length} 个`);
            load();
          }
        })
        .catch(() => {});
    });
  };

  if (all === null) {
    return (
      <div style={{ padding: 60, textAlign: "center" }}>
        <span className="spinner" />
      </div>
    );
  }

  return (
    <>
      {CATS.map((cat) => {
        const list = (all || [])
          .filter((b) => groupKey(b.code) === cat.key)
          .sort((a, b) => (b.rows || 0) - (a.rows || 0));
        const codes = list.map((b) => b.code);
        const isCollapsed = !!collapsed[cat.key];
        return (
          <div key={cat.key} className="card fade-up" style={{ marginBottom: 16, overflow: "hidden" }}>
            <div
              style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 18px", cursor: "pointer" }}
              onClick={(e) => {
                if ((e.target as HTMLElement).closest("button") || (e.target as HTMLElement).closest("input")) return;
                setCollapsed((p) => ({ ...p, [cat.key]: !p[cat.key] }));
              }}
            >
              <span style={{ transition: "transform .15s", transform: isCollapsed ? "rotate(-90deg)" : "none" }}>▾</span>
              <b>{cat.name}</b>
              <span style={{ color: "var(--muted)", fontSize: 13 }}>
                {list.length} 个 · 有数据 {list.filter((b) => b.rows > 0).length}
              </span>
              <span style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center" }}>
                {codes.some((c) => dirty.has(c)) && (
                  <>
                    <span style={{ color: "var(--warn)", fontSize: 12.5 }}>● 有未保存</span>
                    <button
                      className="btn-primary"
                      disabled={saving}
                      onClick={(e) => {
                        e.stopPropagation();
                        saveCat(codes);
                      }}
                    >
                      {saving ? <span className="spinner" /> : "💾 保存"}
                    </button>
                  </>
                )}
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    bulk(codes, (c) => adminPost(`/api/boards/enable?code=${encodeURIComponent(c)}&enabled=true`));
                  }}
                >
                  全开抓取
                </button>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    bulk(codes, (c) => adminPost(`/api/boards/public?code=${encodeURIComponent(c)}&public=true`));
                  }}
                >
                  全开放
                </button>
                <button
                  className="btn-primary"
                  onClick={(e) => {
                    e.stopPropagation();
                    bulk(codes, (c) => adminPost(`/api/boards/refresh?code=${encodeURIComponent(c)}`));
                  }}
                >
                  全部刷新
                </button>
              </span>
            </div>
            {!isCollapsed && (
              <table className="data">
                <thead>
                  <tr>
                    <th>区服</th>
                    <th className="num">行数</th>
                    <th>抓取</th>
                    <th>开放</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((b) => (
                    <tr key={b.code}>
                      <td>
                        {cat.global ? (
                          <span className="badge" style={{ color: "var(--accent)", background: "rgba(52,211,153,0.14)" }}>
                            全服
                          </span>
                        ) : (
                          svName(b.server)
                        )}
                      </td>
                      <td className="num">{fmtNum(b.rows)}</td>
                      <td>
                        <input type="checkbox" checked={!!b.enabled} onChange={(e) => setEnabled(b.code, e.target.checked)} />
                      </td>
                      <td>
                        <input
                          type="checkbox"
                          checked={!!b.publicVisible}
                          onChange={(e) => setPublic(b.code, e.target.checked)}
                        />
                      </td>
                      <td>
                        <button style={{ padding: "4px 12px", fontSize: 13 }} onClick={() => refresh(b.code)}>
                          刷新
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        );
      })}
    </>
  );
}

export default function AdminBoardsPage() {
  const [toastNode, toast] = useToast();
  return (
    <AdminShell title="📊 榜单管理">
      <MarqueeCard toast={toast} />
      <SliceCard toast={toast} />
      <CatsSection toast={toast} />
      {toastNode}
    </AdminShell>
  );
}
