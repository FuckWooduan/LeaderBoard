"use client";

/**
 * 分区排行榜（客户端交互部分）。所有操作（选榜/选分区/汇总/龙虎榜/分页/跨榜搜索）全部透传到 URL，刷新保持。
 * keys 与热力图 grid 由服务端（slice/page.tsx）SSR 预取注入，避免首屏 200 个格子先渲染成红色再变色的闪烁。
 */

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, post } from "@/lib/api";
import { fmtNum, fmtTime } from "@/lib/format";
import { withCaptcha } from "@/lib/captcha";
import { DataTable } from "@/components/DataTable";
import { Pager } from "@/components/Pager";
import { DistrictTag } from "@/components/cells";
import type { Field, Row } from "@/components/cells";

const PAGE_SIZE = 100;
const TOTAL_SLICES = 200;

export interface SliceKey {
  rankKey: string;
  name?: string;
  label?: string;
  dynamic?: boolean;
  visible?: boolean;
  hidden?: boolean;
  brawl?: boolean;
  lastFetch?: number;
}

export interface SliceGrid {
  coverage: number[];
  maxSource: number;
}

function cellColor(c: number, maxc: number): string {
  if (!c) return "#7f1d1d";
  const t = maxc > 1 ? Math.sqrt(c / maxc) : 1;
  const h = 120 + 16 * t;
  const s = 42 + 26 * t;
  const l = 64 - 38 * t;
  return `hsl(${h.toFixed(1)},${s.toFixed(1)}%,${l.toFixed(1)}%)`;
}

export function SliceView({
  initialKeys,
  initialKey,
  initialGrid,
}: {
  initialKeys?: SliceKey[];
  initialKey?: string;
  initialGrid?: SliceGrid | null;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();

  // ── URL 即状态源 ──
  const urlKey = params.get("key") || "";
  const urlPartition = params.get("p"); // 0-based 字符串
  const urlView = params.get("view") || ""; // "dragon" | ""
  const urlPage = Math.max(1, parseInt(params.get("page") || "1", 10) || 1);
  const urlQuery = params.get("q") || ""; // 跨榜玩家搜索

  const [keys, setKeys] = useState<SliceKey[]>(initialKeys ?? []);
  const [rankKey, setRankKey] = useState(initialKey || urlKey);
  const [grid, setGrid] = useState<SliceGrid | null>(initialGrid ?? null);
  const [board, setBoard] = useState<{ fields: Field[]; rows: Row[]; total: number; updatedAt?: number } | null>(null);
  const [boardTitle, setBoardTitle] = useState("");
  const [playerRows, setPlayerRows] = useState<Row[] | null>(null);
  const [pred, setPred] = useState<any>(null);
  const [fetchBusy, setFetchBusy] = useState(false);
  const [toast, setToast] = useState("");
  const [searchBox, setSearchBox] = useState(urlQuery);
  const hydratedRef = useRef(Boolean(initialKeys?.length)); // SSR 已注入 keys → 跳过首次客户端拉取

  const partition = urlPartition !== null && urlPartition !== "" ? parseInt(urlPartition, 10) : null;
  const view: "" | "dragon" = urlView === "dragon" ? "dragon" : "";
  const meta = keys.find((k) => k.rankKey === rankKey);

  // ── keys（SSR 注入则跳过首次；URL 指定则用指定）──
  const loadKeys = useCallback(async () => {
    try {
      const all: SliceKey[] = await api("/api/public/slice/keys");
      const vis = (all || []).filter((k) => k.visible !== false);
      setKeys(vis);
      setRankKey((prev) => {
        const want = urlKey || prev;
        return want && vis.some((k) => k.rankKey === want) ? want : vis[0]?.rankKey || "";
      });
    } catch {}
  }, [urlKey]);

  useEffect(() => {
    if (hydratedRef.current) {
      hydratedRef.current = false; // SSR 首屏已注入 keys + grid
      return;
    }
    void loadKeys();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // URL 的 key 变化时同步 rankKey
  useEffect(() => {
    if (urlKey && urlKey !== rankKey && keys.some((k) => k.rankKey === urlKey)) {
      setRankKey(urlKey);
    }
    setSearchBox(urlQuery);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlKey, urlQuery, keys]);

  // ── grid（4s 轮询；SSR 已注入首份数据，轮询只做后续刷新）──
  useEffect(() => {
    if (!rankKey) return;
    let live = true;
    const poll = () =>
      api(`/api/public/slice/grid?rankKey=${encodeURIComponent(rankKey)}`)
        .then((d) => live && setGrid({ coverage: d.coverage || [], maxSource: d.maxSource || 0 }))
        .catch(() => {});
    poll();
    const t = setInterval(poll, 4000);
    return () => {
      live = false;
      clearInterval(t);
    };
  }, [rankKey]);

  // ── 预测进度（仅隐藏榜）──
  const predTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollPred = useCallback(() => {
    if (!rankKey) return;
    api(`/api/public/slice/predict-status?rankKey=${encodeURIComponent(rankKey)}`)
      .then((d) => {
        setPred(d);
        if (d.running) {
          if (!predTimer.current) predTimer.current = setInterval(pollPred, 3000);
        } else if (predTimer.current) {
          clearInterval(predTimer.current);
          predTimer.current = null;
        }
      })
      .catch(() => {});
  }, [rankKey]);

  useEffect(() => {
    if (meta?.hidden) pollPred();
    return () => {
      if (predTimer.current) {
        clearInterval(predTimer.current);
        predTimer.current = null;
      }
    };
  }, [meta?.hidden, pollPred]);

  // ── 榜数据（依 URL 状态加载）──
  const loadBoard = useCallback(() => {
    if (!rankKey) return;
    const nm = meta?.name || rankKey;
    // 跨榜玩家搜索优先
    if (urlQuery) {
      setBoardTitle(`含「${urlQuery}」的玩家 · 跨榜分区情况（模糊搜索）`);
      api(`/api/public/slice/player?name=${encodeURIComponent(urlQuery)}`)
        .then((d) => setPlayerRows(d.rows || []))
        .catch(() => setPlayerRows([]));
      return;
    }
    setPlayerRows(null);
    let qs = `/api/public/slice/board?rankKey=${encodeURIComponent(rankKey)}&page=${urlPage}`;
    let title: string;
    if (view === "dragon") {
      qs += "&view=dragon";
      title = `${nm} · 🐲 龙虎榜（各分区第1名→第2名…）`;
    } else if (partition === null) {
      qs += "&all=true";
      title = `${nm} · 全部分区汇总（按分数）`;
    } else {
      qs += `&partition=${partition}`;
      title = `${nm} · 分区 ${partition + 1}`;
    }
    setBoardTitle(title);
    api(qs)
      .then((d) => {
        setBoard(d);
        setBoardTitle(title + (d?.updatedAt ? ` · 更新于 ${fmtTime(d.updatedAt)}` : ""));
      })
      .catch(() => {});
  }, [rankKey, meta, urlQuery, urlPage, view, partition]);

  useEffect(() => {
    loadBoard();
  }, [loadBoard]);

  /** 合并写 URL。 */
  const pushUrl = useCallback(
    (patch: Record<string, string | number | null>) => {
      const sp = new URLSearchParams(params.toString());
      for (const [k, v] of Object.entries(patch)) {
        if (v === null || v === "" || (k === "page" && Number(v) === 1)) sp.delete(k);
        else sp.set(k, String(v));
      }
      const s = sp.toString();
      router.replace(s ? `${pathname}?${s}` : pathname, { scroll: false });
    },
    [params, pathname, router]
  );

  const changeKey = (k: string) => pushUrl({ key: k, p: null, view: null, page: 1, q: null });
  const openPartition = (p: number) => pushUrl({ p, view: null, page: 1, q: null });
  const openAggregate = () => pushUrl({ p: null, view: null, page: 1, q: null });
  const openDragon = () => pushUrl({ p: null, view: "dragon", page: 1, q: null });
  const doPlayerSearch = (n: string) => pushUrl({ q: n.trim(), p: null, view: null, page: 1 });

  // ── 抓取此分区（人机验证）──
  const fetchPartition = async () => {
    if (!rankKey || partition === null) return;
    setFetchBusy(true);
    try {
      const gt = await withCaptcha();
      const d = await post("/api/public/slice/fetch-partition", { rankKey, partition, ...gt });
      setToast(d.message || (d.ok ? "已开始抓取" : "抓取触发失败"));
      if (d.ok) setTimeout(loadBoard, 11000);
    } catch (e: any) {
      setToast(`触发失败：${e.message}`);
    } finally {
      setFetchBusy(false);
      setTimeout(() => setToast(""), 5000);
    }
  };

  const predictNow = async () => {
    const email = prompt("重新预测「所有隐藏榜」（较吃性能，后台异步跑）。\n跑完把识别出的隐藏玩家身份邮件发给你。请输入邮箱：");
    if (!email) return;
    try {
      const gt = await withCaptcha();
      const d = await post("/api/public/slice/predict-now", { email: email.trim(), ...gt });
      alert(d.message || (d.ok ? "已开始" : "启动失败"));
      if (d.ok) pollPred();
    } catch (e: any) {
      alert(`失败：${e.message}`);
    }
  };

  const subscribe = async (charName: string) => {
    if (partition === null || !rankKey) return;
    const email = prompt(`订阅「${charName}」在 分区${partition + 1} 的排名变动\n排名一变就发邮件给你。请输入邮箱：`);
    if (!email) return;
    try {
      const gt = await withCaptcha();
      const d = await post("/api/public/slice/subscribe", { email: email.trim(), rankKey, partition, charName, ...gt });
      alert(d.message || (d.ok ? "订阅成功" : "订阅失败"));
    } catch (e: any) {
      alert(`订阅失败：${e.message}`);
    }
  };

  const covered = grid ? grid.coverage.filter((c) => c > 0).length : 0;
  const totalPages = Math.max(1, Math.ceil((board?.total || 0) / PAGE_SIZE));

  return (
    <div className="fade-up">
      {/* 工具栏 */}
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 14 }}>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
          <label style={{ fontSize: 13, color: "var(--muted)" }}>排行榜</label>
          <select value={rankKey} onChange={(e) => changeKey(e.target.value)}>
            {!keys.length && <option value="">（暂无榜单，正在后台获取…）</option>}
            {keys.map((k) => (
              <option key={k.rankKey} value={k.rankKey}>
                {k.name || k.rankKey}
              </option>
            ))}
          </select>
        </span>
        <span style={{ color: "var(--muted)", fontSize: 13 }}>
          {rankKey && `${meta?.lastFetch ? `最新抓取 ${fmtTime(meta.lastFetch)} (北京时间)` : "暂未抓取"}`}
        </span>
        {partition !== null && !urlQuery && (
          <button onClick={fetchPartition} disabled={fetchBusy} style={{ marginLeft: "auto" }} title="只抓取该分区（需人机验证）">
            {fetchBusy ? "提交中…" : `⚡ 抓取分区 ${partition + 1}`}
          </button>
        )}
        <button onClick={loadKeys}>↻ 刷新</button>
        {toast && <span style={{ fontSize: 13, color: "var(--accent)" }}>{toast}</span>}
      </div>

      {/* 进度 */}
      <div className="card" style={{ padding: "16px 18px", marginBottom: 14 }}>
        <div style={{ fontSize: 13, color: "var(--muted)", marginBottom: 8 }}>
          分区获取进度 已覆盖 <b style={{ color: "var(--text)" }}>{covered}</b> / {TOTAL_SLICES} 个分区
        </div>
        <div className="progress">
          <i style={{ width: `${Math.round((covered / TOTAL_SLICES) * 1000) / 10}%` }} />
        </div>
      </div>

      {/* 预测面板（仅隐藏榜） */}
      {meta?.hidden && (
        <div className="card" style={{ padding: "16px 18px", marginBottom: 14 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap", marginBottom: 12 }}>
            <b>🔮 隐藏玩家预测</b>
            <span style={{ color: "var(--muted)", fontSize: 13 }}>
              {pred?.running
                ? "预测进行中…"
                : pred?.lastPredictedAt
                  ? `最近预测 ${fmtTime(pred.lastPredictedAt)}（北京时间）`
                  : "尚未预测"}
            </span>
            {!pred?.running && pred?.nextPredictAt ? (
              <span style={{ color: "var(--muted)", fontSize: 13 }}>
                · 下次预测约 {fmtTime(pred.nextPredictAt)}
              </span>
            ) : null}
            <button onClick={predictNow} style={{ marginLeft: "auto", padding: "5px 12px", fontSize: 13 }}>
              🔄 重新预测
            </button>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: meta?.brawl ? "1fr 1fr" : "1fr", gap: 14 }}>
            <div>
              <div style={{ fontSize: 12, color: "var(--muted)", marginBottom: 4 }}>
                身份进度 已识别 <b>{pred?.identityDone || 0}</b> / {pred?.total || 0}（{pred?.identityPercent || 0}%）
              </div>
              <div className="progress">
                <i style={{ width: `${pred?.identityPercent || 0}%` }} />
              </div>
            </div>
            {/* 分数进度仅乱斗榜有意义（其余榜没有分数预测，不显示空进度条） */}
            {meta?.brawl && (
              <div>
                <div style={{ fontSize: 12, color: "var(--muted)", marginBottom: 4 }}>
                  分数进度 已预测 <b>{pred?.scoreDone || 0}</b> / {pred?.total || 0}（{pred?.scorePercent || 0}%）
                </div>
                <div className="progress">
                  <i style={{ width: `${pred?.scorePercent || 0}%` }} />
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* 热力图 */}
      <div className="card" style={{ padding: 18, marginBottom: 16 }}>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>
          {meta ? `${meta.name || rankKey}（${meta.dynamic ? "动态" : "静态"}，共 ${TOTAL_SLICES} 个分区）` : "请选择一个排行榜"}
        </div>
        <div style={{ fontSize: 12, color: "var(--muted)", marginBottom: 12 }}>
          分区 1–{TOTAL_SLICES}（红=该分区暂无我们的号→抓不了榜；越深=越多账号落在该分区，可抓）。
          <b>点格子看该分区排行榜。</b>
        </div>
        <div className="slice-grid">
          {Array.from({ length: TOTAL_SLICES }, (_, i) => {
            const c = grid?.coverage[i] || 0;
            const sel = partition === i && !urlQuery;
            return (
              <div
                key={i}
                className={`slice-cell${sel ? " sel" : ""}`}
                style={{ background: cellColor(c, grid?.maxSource || 0), cursor: c ? "pointer" : "default" }}
                title={`分区 ${i + 1} · ${c} 个号在此分区${c ? "（点击看榜）" : "（暂无号→抓不了）"}`}
                onClick={() => c && openPartition(i)}
              >
                {i + 1}
              </div>
            );
          })}
        </div>
      </div>

      {/* 视图切换 */}
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 14 }}>
        <button className={partition === null && view === "" && !urlQuery ? "btn-primary" : ""} onClick={openAggregate}>
          📊 全部分区汇总
        </button>
        <button className={view === "dragon" ? "btn-primary" : ""} onClick={openDragon} title="各分区第1名→各分区第2名→…">
          🐲 龙虎榜
        </button>
        <span style={{ color: "var(--muted)", fontSize: 13 }}>或点上方格子看单个分区</span>
        <span style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center" }}>
          <input
            value={searchBox}
            onChange={(e) => setSearchBox(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && doPlayerSearch(searchBox)}
            placeholder="🔍 玩家跨榜查询"
            title="输入角色名，查该玩家在所有分区榜里的名次"
            style={{ minWidth: 180, flex: "0 1 220px" }}
          />
          <button onClick={() => doPlayerSearch(searchBox)}>查</button>
          {urlQuery && <button onClick={openAggregate} title="清除搜索">✕</button>}
        </span>
      </div>

      {/* 榜表 */}
      <div style={{ marginBottom: 6, color: "var(--muted)", fontSize: 14 }}>{boardTitle}</div>
      {playerRows ? (
        <div className="table-wrap card">
          <table className="data">
            <thead>
              <tr>
                <th>角色名</th>
                <th>排行榜</th>
                <th>分区</th>
                <th>名次</th>
                <th>分数</th>
                <th>区服</th>
                <th>战队</th>
              </tr>
            </thead>
            <tbody>
              {playerRows.length ? (
                playerRows.map((r, i) => (
                  <tr key={i}>
                    <td>
                      <b>{r.char_name || "—"}</b>
                    </td>
                    <td>{keys.find((k) => k.rankKey === r.rank_key)?.name || r.rank_key}</td>
                    <td>{r.partition}</td>
                    <td className="rank num">{r.rank}</td>
                    <td className="num">{fmtNum(r.score_number)}</td>
                    <td>
                      <DistrictTag v={r.login_id} />
                    </td>
                    <td>{r.team_name || "—"}</td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={7} style={{ textAlign: "center", color: "var(--muted)", padding: 40 }}>
                    在已抓到的分区榜里没找到名字含「{urlQuery}」的玩家
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      ) : (
        <>
          {board?.rows?.some((r) => r.power_missing) && (
            <div
              className="card"
              style={{
                padding: "8px 12px",
                marginBottom: 10,
                fontSize: 12.5,
                color: "var(--muted)",
                background: "rgba(96,165,250,0.06)",
                borderColor: "rgba(96,165,250,0.25)",
              }}
            >
              ℹ️ 「战力 / VIP / 境界」来自该玩家<b>所在区服战力榜的前 1000 名</b>（按 gcid 关联）。
              显示「未上榜」的玩家是因为没进本区战力榜前 1000（双线七区等小区常见），并非数据抓取故障。
            </div>
          )}
          <DataTable
            fields={board?.fields || []}
            rows={board?.rows || []}
            total={board?.total || 0}
            emptyText="该分区暂未抓到数据（后台抓取中或该分区无号）"
            extraHeader={partition !== null ? "订阅" : undefined}
            extraCell={
              partition !== null
                ? (row) => (
                    <button style={{ padding: "4px 10px", fontSize: 12 }} onClick={() => void subscribe(String(row.char_name || ""))}>
                      🔔订阅
                    </button>
                  )
                : undefined
            }
          />
          {board && <Pager page={urlPage} totalPages={totalPages} total={board.total || 0} onPage={(p) => pushUrl({ page: p })} />}
        </>
      )}
    </div>
  );
}
