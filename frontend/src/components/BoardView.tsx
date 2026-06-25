"use client";

/** 战力/经验/天梯/战队 四类主榜共用视图。所有状态（区服/页码/搜索/VIP/排序）透传到 URL，刷新保持。 */

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, post } from "@/lib/api";
import { withCaptcha } from "@/lib/captcha";
import { fmtNum, fmtTime } from "@/lib/format";
import { SERVER_ORDER } from "@/lib/servers";
import { DataTable } from "./DataTable";
import { Pager } from "./Pager";
import type { Field, Row } from "./cells";

const PAGE_SIZE = 100;

export interface BoardData {
  code?: string;
  page: number;
  total: number;
  lastFetch?: number;
  fields: Field[];
  rows: Row[];
}

export interface ServerOption {
  id: string;
  name: string;
}

export function BoardView({
  tab,
  initialServers,
  initialData,
}: {
  tab: "power" | "exp" | "ladder" | "team";
  initialServers?: ServerOption[];
  initialData?: BoardData;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();

  // URL 即状态源（刷新保持）
  const server = tab === "ladder" ? "" : params.get("server") || "";
  const page = Math.max(1, parseInt(params.get("page") || "1", 10) || 1);
  const q = params.get("q") || "";
  const vip = params.get("vip") || "";
  const sort = (params.get("sort") as "normal" | "adventure") || "normal";
  const isDefault = page === 1 && !q && !vip && (tab !== "team" || sort === "normal");

  const [servers, setServers] = useState<ServerOption[]>(() => {
    const list = initialServers ? [...initialServers] : [];
    list.sort((a, b) => (SERVER_ORDER[a.name] ?? 999) - (SERVER_ORDER[b.name] ?? 999));
    return list;
  });
  const [data, setData] = useState<BoardData | null>(initialData ?? null);
  const [loading, setLoading] = useState(!initialData);
  const [failed, setFailed] = useState(false);
  const [searchBox, setSearchBox] = useState(q);
  const [refreshMsg, setRefreshMsg] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  // SSR 注入仅匹配「默认视图」；URL 带非默认参数时仍需客户端首拉
  const hydratedRef = useRef(Boolean(initialData) && isDefault);
  const hydratedServersRef = useRef(Boolean(initialServers));
  const searchTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // 区服下拉
  useEffect(() => {
    if (tab === "ladder") return;
    if (hydratedServersRef.current) {
      hydratedServersRef.current = false;
      return;
    }
    api(`/api/public/servers?tab=${tab}`)
      .then((list: ServerOption[]) => {
        list.sort((a, b) => (SERVER_ORDER[a.name] ?? 999) - (SERVER_ORDER[b.name] ?? 999));
        setServers(list);
      })
      .catch(() => {});
  }, [tab]);

  const load = useCallback(() => {
    setLoading(true);
    setFailed(false);
    let qs = `/api/public/tab?tab=${tab}&server=${encodeURIComponent(server)}&page=${page}`;
    if (q) qs += `&q=${encodeURIComponent(q)}`;
    if (tab === "team") qs += `&sort=${sort}`;
    if (vip !== "") qs += `&vip=${encodeURIComponent(vip)}`;
    api(qs)
      .then((d: BoardData) => {
        const filtering = Boolean(q) || vip !== "";
        const base = ((d.page || 1) - 1) * PAGE_SIZE;
        d.rows?.forEach((r, i) => (r._fidx = filtering ? base + i + 1 : null));
        setData(d);
        setLoading(false);
      })
      .catch(() => {
        setLoading(false);
        setFailed(true);
        setData(null);
      });
  }, [tab, server, page, q, vip, sort]);

  // 任一 URL 状态变化即加载（SSR 默认视图首轮跳过）
  useEffect(() => {
    setSearchBox(q);
    if (hydratedRef.current) {
      hydratedRef.current = false;
      return;
    }
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load]);

  /** 合并写 URL（透传 + 刷新保持）；变更筛选/区服时回到第 1 页。 */
  const pushUrl = useCallback(
    (patch: Record<string, string | number | null>) => {
      const sp = new URLSearchParams(params.toString());
      for (const [k, v] of Object.entries(patch)) {
        if (v === null || v === "" || (k === "page" && Number(v) === 1) || (k === "sort" && v === "normal")) {
          sp.delete(k);
        } else {
          sp.set(k, String(v));
        }
      }
      const s = sp.toString();
      router.replace(s ? `${pathname}?${s}` : pathname, { scroll: false });
    },
    [params, pathname, router]
  );

  const totalPages = Math.max(1, Math.ceil((data?.total || 0) / PAGE_SIZE));

  const doSearch = (v: string) => {
    setSearchBox(v);
    clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => pushUrl({ q: v.trim(), page: 1 }), 350);
  };

  const forceRefresh = async () => {
    if (!data?.code) {
      setRefreshMsg("请先打开一个榜");
      return;
    }
    const code = data.code;
    setRefreshing(true);
    setRefreshMsg("请完成人机验证…");
    try {
      const gt = await withCaptcha();
      setRefreshMsg("正在提交刷新…");
      const r = await post("/api/public/board/refresh", { code, ...gt });
      setRefreshMsg(r.message || (r.ok ? "已加入刷新队列" : "刷新失败"));
      if (r.ok) {
        setTimeout(() => {
          setRefreshing(false);
          load();
          setRefreshMsg("已更新 ✓");
        }, 12000);
      } else {
        setRefreshing(false);
      }
    } catch (e: any) {
      setRefreshing(false);
      setRefreshMsg(`刷新失败：${e.message}`);
    }
  };

  return (
    <div className="fade-up">
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 14 }}>
        {tab !== "ladder" && (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <label style={{ fontSize: 13, color: "var(--muted)" }}>区服</label>
            <select value={server} onChange={(e) => pushUrl({ server: e.target.value, page: 1 })}>
              <option value="">全部</option>
              {servers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name || `${s.id} 区`}
                </option>
              ))}
            </select>
          </span>
        )}
        <button onClick={load}>↻ 刷新</button>
        <button
          onClick={forceRefresh}
          disabled={refreshing}
          title="立即重新抓取该榜（单服=本区，跨服=全部），需人机验证，约10秒后更新"
        >
          ⟳ 强制刷新
        </button>
        <span style={{ fontSize: 13, color: "var(--muted)" }}>{refreshMsg}</span>
        {tab === "team" && (
          <span style={{ display: "inline-flex", gap: 6 }}>
            {(["normal", "adventure"] as const).map((s) => (
              <button key={s} className={sort === s ? "btn-primary" : ""} onClick={() => pushUrl({ sort: s, page: 1 })}>
                {s === "normal" ? "按普通排序" : "按冒险排序"}
              </button>
            ))}
          </span>
        )}
        {(tab === "power" || tab === "exp") && (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <label style={{ fontSize: 13, color: "var(--muted)" }}>VIP</label>
            <select value={vip} onChange={(e) => pushUrl({ vip: e.target.value, page: 1 })}>
              <option value="">全部VIP</option>
              {Array.from({ length: 19 }, (_, i) => (
                <option key={i} value={i}>
                  V{i}
                </option>
              ))}
            </select>
          </span>
        )}
        <span style={{ fontSize: 13, color: "var(--muted)", marginLeft: "auto" }}>
          {data?.lastFetch ? `上次上分时间 ${fmtTime(data.lastFetch)} (北京时间)` : data ? "暂未抓取" : ""}
        </span>
        <input
          value={searchBox}
          onChange={(e) => doSearch(e.target.value)}
          placeholder="🔍 搜角色名 / 战队"
          title="模糊搜索角色名或战队，搜索结果保留原榜名次（名次不变）"
          style={{ minWidth: 220, flex: "0 1 260px" }}
        />
      </div>

      {loading ? (
        <div className="card" style={{ padding: 0, overflow: "hidden" }}>
          {Array.from({ length: 8 }, (_, i) => (
            <div key={i} className="skeleton" style={{ height: 40, margin: 12, borderRadius: 8 }} />
          ))}
        </div>
      ) : (
        <>
          <DataTable
            fields={data?.fields || []}
            rows={data?.rows || []}
            total={data?.total || 0}
            emptyText={
              failed
                ? "该榜暂未开放或不存在"
                : q
                  ? `没有匹配「${q}」的角色或战队`
                  : "暂无数据（该榜可能尚未抓取到本周期数据）"
            }
          />
          {data && (
            <Pager page={data.page} totalPages={totalPages} total={data.total} onPage={(p) => pushUrl({ page: p })} />
          )}
        </>
      )}
    </div>
  );
}
