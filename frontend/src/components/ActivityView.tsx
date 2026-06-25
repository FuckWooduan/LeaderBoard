"use client";

/**
 * 活动通知页：
 * - 每页 10 条，顶部 + 底部双分页；
 * - 两种解析视图：🎬 SWF 动态（默认，Ruffle 后台预热）/ 🖼 长图；
 * - 长图与 SWF 走带扩展名的缓存友好地址（/api/public/activity/{id}.png|.swf，Cloudflare 边缘缓存）。
 */

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { api, post } from "@/lib/api";
import { fmtTime } from "@/lib/format";
import { withCaptcha } from "@/lib/captcha";
import { Lightbox } from "@/components/Modal";

const PAGE_SIZE = 10;
// 预热整页的 SWF（HEAD 探测并行发出，不串行），让滚到哪都已就绪；Ruffle 播放器仍按视口懒实例化避免卡顿。
const RUFFLE_PRELOAD_COUNT = PAGE_SIZE;

export interface Activity {
  activityId: number;
  title?: string;
  description?: string;
  startTime?: string;
  endTime?: string;
  hasImage?: boolean;
  firstSeenAt?: number;
}

export interface ActivityPage {
  ok?: boolean;
  total?: number;
  lastFetchAt?: number;
  nextDetectAt?: number;
  subscriberCount?: number;
  message?: string;
  activities?: Activity[];
}

function strip(s?: string): string {
  return String(s || "")
    .replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/g, " ")
    .trim();
}

/** 长图：404 时后台异步渲染，自动重试（2.5s 起递增，最多 5 次）。 */
function ActImage({ id, onZoom }: { id: number; onZoom: (src: string) => void }) {
  const base = `/api/public/activity/${id}.png`;
  const [src, setSrc] = useState(base);
  const [dead, setDead] = useState(false);
  const retryRef = useRef(0); // 用 ref 计数，避免 onError 闭包捕获陈旧的 retry 值导致重试卡死
  if (dead) return null;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={src}
      alt="活动长图"
      loading="lazy"
      style={{
        maxWidth: "100%",
        border: "1px solid var(--border)",
        borderRadius: 8,
        cursor: "zoom-in",
        display: "block",
        marginTop: 8,
      }}
      onClick={() => onZoom(src)}
      onError={() => {
        const n = retryRef.current;
        if (n >= 5) {
          setDead(true);
          return;
        }
        retryRef.current = n + 1;
        setTimeout(() => setSrc(`${base}?_=${Date.now()}`), 2500 + n * 1800);
      }}
    />
  );
}

// ── Ruffle（自托管 /ruffle/ruffle.js）────────────────────────────────────────

let ruffleLoading: Promise<any> | null = null;
const RUFFLE_ASSET_BASE = "/ruffle/20260611/";
const RUFFLE_SCRIPT_URL = `${RUFFLE_ASSET_BASE}ruffle.js`;
// 自托管 CJK 字体：必须经 /ruffle/ 前缀分发——只有该前缀在 Cloudflare 被放行（/fonts 等其他路径会被
// WAF 拦成 403，导致 Ruffle fetch 字体失败）。源文件 tracked 在 public/fonts/，由 prebuild 拷进
// 构建期重建的 public/ruffle/fonts/（public/ruffle 每次 build 会被 prebuild 清空重建）。
const RUFFLE_FONT_BASE = "/ruffle/fonts/";

function loadRuffle(): Promise<any> {
  if (ruffleLoading) return ruffleLoading;
  ruffleLoading = new Promise((resolve, reject) => {
    const w = window as any;
    if (w.RufflePlayer?.newest) {
      resolve(w.RufflePlayer);
      return;
    }
    w.RufflePlayer = w.RufflePlayer || {};
    w.RufflePlayer.config = {
      publicPath: RUFFLE_ASSET_BASE,
      autoplay: "on",
      unmuteOverlay: "hidden",
      letterbox: "on",
      // Ruffle 不内置 CJK 字形：设备字体（device font）渲染的动态中文文本（活动 tip/说明）
      // 会显示为空白。提供自托管 OTF 并映射到默认字体槽（_sans/_serif/_typewriter 回退），
      // 即可像 ffdec / Adobe Flash Player 一样正常渲染中文提示。字体内部全名 = "Noto Sans SC"。
      fontSources: [`${RUFFLE_FONT_BASE}NotoSansSC-Subset.otf`],
      defaultFonts: {
        sans: ["Noto Sans SC"],
        serif: ["Noto Sans SC"],
        typewriter: ["Noto Sans SC"],
      },
    };
    const s = document.createElement("script");
    s.src = RUFFLE_SCRIPT_URL;
    s.onload = () => resolve(w.RufflePlayer);
    s.onerror = () => {
      ruffleLoading = null;
      reject(new Error("Ruffle 播放器加载失败"));
    };
    document.head.appendChild(s);
  });
  return ruffleLoading;
}

function warmRuffle(activityIds: number[]) {
  const warm = () => {
    loadRuffle().catch(() => {});
    // 预取 8.3MB CJK 字体：首个播放器实例化时 Ruffle 会同步加载它，提前进浏览器缓存可显著加快首帧渲染
    fetch(`${RUFFLE_FONT_BASE}NotoSansSC-Subset.otf`).catch(() => {});
    for (const id of activityIds.slice(0, RUFFLE_PRELOAD_COUNT)) {
      fetch(`/api/public/activity/${id}.swf`, { method: "HEAD" }).catch(() => {});
    }
  };
  const idle = window.requestIdleCallback;
  if (idle) {
    idle(warm, { timeout: 1200 });
  } else {
    window.setTimeout(warm, 400);
  }
}

// SWF 播放器/占位用「宽高比」自适应高度（而非写死像素高），否则移动端窄屏下 letterbox 会在上下补出大片黑边。
// 多数活动 SWF 为 1000×650；播放器就绪后会用真实分辨率覆盖，彻底消除黑边。
const SWF_ASPECT = "1000 / 650";

/**
 * SWF 在线播放：滚动进入视口后自动异步实例化 Ruffle（无需点击）。
 * 鼠标交互始终开启（pointerEvents:auto），与 Adobe Flash Player 一致：
 * 悬停触发活动原生 hover 提示、点击互动、音频解锁均可用；
 * 中文 tip/说明 依赖全局 Ruffle config 注入的 CJK 字体（见 loadRuffle）。
 */
function SwfPlayer({ id, title }: { id: number; title: string }) {
  const holder = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const startedRef = useRef(false); // 防止 IntersectionObserver 多次触发重复实例化
  const [state, setState] = useState<"idle" | "loading" | "playing" | "error">("idle");
  const [hint, setHint] = useState("");
  const swfUrl = `/api/public/activity/${id}.swf`;

  const start = useCallback(async () => {
    if (startedRef.current) return;
    startedRef.current = true;
    setState("loading");
    setHint("正在准备 SWF…");
    // 并行预热播放器（脚本+WASM+字体），与下方 SWF 就绪探测的网络往返重叠，减少串行等待
    const rufflePromise = loadRuffle();
    try {
      // 先探测 SWF 是否就绪（404 → 后台下载中，自动重试 ~40s）
      for (let i = 0; ; i++) {
        const head = await fetch(swfUrl, { method: "HEAD" });
        if (head.ok) break;
        if (i >= 12) throw new Error("SWF 暂未就绪（源站下载中），请稍后再试");
        setHint(`SWF 获取中…（${i + 1}/12）`);
        await new Promise((r) => setTimeout(r, 3000));
      }
      setHint("加载播放器…");
      const RufflePlayer = await rufflePromise;
      const player = RufflePlayer.newest().createPlayer();
      player.style.width = "100%";
      player.style.aspectRatio = SWF_ASPECT; // 跟随宽度按比例定高，移动端不再有上下黑边
      player.style.height = "auto";
      player.style.pointerEvents = "auto";
      // SWF 元数据就绪后用真实分辨率设精确宽高比，letterbox 无可补 → 黑边彻底消失（不同活动分辨率不一）
      player.addEventListener("loadedmetadata", () => {
        const md = player.metadata;
        if (md && md.width > 0 && md.height > 0) {
          player.style.aspectRatio = `${md.width} / ${md.height}`;
        }
      });
      const unlockAudio = () => {
        try {
          player.volume = 100;
          player.ruffle().volume = 100;
          player.ruffle().resume?.();
          player.play?.();
        } catch {
          // Browser autoplay policies may still require a user gesture.
        }
      };
      player.addEventListener("pointerdown", unlockAudio);
      holder.current!.innerHTML = "";
      holder.current!.appendChild(player);
      await player.ruffle().load(swfUrl);
      unlockAudio();
      setState("playing");
    } catch (e: any) {
      startedRef.current = false; // 失败后允许点击重试
      setState("error");
      setHint(e.message || "播放失败");
    }
  }, [swfUrl]);

  // 进入视口才自动加载（提前 600px 预热），避免整页播放器同时实例化导致卡顿
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((en) => en.isIntersecting)) {
          io.disconnect();
          start();
        }
      },
      { rootMargin: "600px 0px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [start]);

  const [imgFailed, setImgFailed] = useState(false);
  const pngSrc = `/api/public/activity/${id}.png`;

  return (
    <div ref={rootRef} style={{ marginTop: 8 }}>
      {state !== "playing" && (
        <div
          onClick={state === "error" ? start : undefined}
          style={{
            border: "1px dashed var(--border-strong)",
            borderRadius: 10,
            overflow: "hidden",
            cursor: state === "error" ? "pointer" : "default",
            background: "var(--hover)",
            position: "relative",
            aspectRatio: imgFailed ? undefined : SWF_ASPECT,
            minHeight: imgFailed ? undefined : 120,
          }}
        >
          {!imgFailed && (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src={pngSrc}
              alt="活动长图预览"
              onError={() => setImgFailed(true)}
              style={{
                width: "100%",
                height: "100%",
                objectFit: "cover",
                objectPosition: "top",
                display: "block",
              }}
            />
          )}
          <div
            style={{
              position: imgFailed ? "static" : "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              padding: imgFailed ? "38px 16px" : "0",
              textAlign: "center",
              background: imgFailed ? undefined : "rgba(0,0,0,0.45)",
            }}
          >
            {state === "error" ? (
              <span style={{ color: "var(--danger)", fontSize: 13 }}>⚠ {hint}（点击重试）</span>
            ) : (
              <>
                <span className="spinner" />{" "}
                <span
                  style={{
                    color: imgFailed ? "var(--muted)" : "#fff",
                    fontSize: 13,
                    marginLeft: 8,
                  }}
                >
                  {hint || `正在加载「${title}」…`}
                </span>
              </>
            )}
          </div>
        </div>
      )}
      <div style={{ position: "relative", borderRadius: 10, overflow: "hidden" }}>
        <div ref={holder} />
      </div>
    </div>
  );
}

export function ActivityView({ initial }: { initial?: ActivityPage }) {
  const [page, setPage] = useState(0);
  const [q, setQ] = useState("");
  const [search, setSearch] = useState("");
  const [data, setData] = useState<ActivityPage | null>(initial ?? null);
  const [loading, setLoading] = useState(!initial);
  const [view, setView] = useState<"image" | "swf">("swf");
  const [zoom, setZoom] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [subMsg, setSubMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [subBusy, setSubBusy] = useState(false);
  const hydratedRef = useRef(Boolean(initial));

  const load = useCallback((p: number, query: string, scroll = true) => {
    setLoading(true);
    api(`/api/public/activities?page=${p}&size=${PAGE_SIZE}&q=${encodeURIComponent(query)}`)
      .then((d) => {
        setData(d);
        setLoading(false);
        if (scroll) window.scrollTo({ top: 0, behavior: "smooth" });
      })
      .catch(() => {
        setData({ ok: false, activities: [], message: "活动加载失败，请刷新重试" });
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    if (hydratedRef.current) {
      hydratedRef.current = false; // SSR 首屏已注入
      return;
    }
    load(0, "", false);
  }, [load]);

  const doSearch = () => {
    setSearch(q.trim());
    setPage(0);
    load(0, q.trim());
  };

  const subscribe = async () => {
    if (!email.includes("@")) {
      setSubMsg({ text: "请填写有效邮箱", ok: false });
      return;
    }
    setSubBusy(true);
    setSubMsg({ text: "订阅中…", ok: true });
    try {
      const gt = await withCaptcha();
      const r = await post("/api/public/activity/subscribe", { email: email.trim(), ...gt });
      setSubMsg({ text: r.message || (r.ok ? "订阅成功" : "订阅失败"), ok: r.ok });
      if (r.subscriberCount != null) setData((d) => ({ ...d, subscriberCount: r.subscriberCount }));
    } catch (e: any) {
      setSubMsg({ text: e.message || "订阅失败", ok: false });
    } finally {
      setSubBusy(false);
    }
  };

  const unsubscribe = async () => {
    if (!email.includes("@")) {
      setSubMsg({ text: "请填写要退订的邮箱", ok: false });
      return;
    }
    setSubBusy(true);
    try {
      const r = await post("/api/public/activity/unsubscribe", { email: email.trim() });
      setSubMsg({ text: r.message || (r.ok ? "已退订" : "退订失败"), ok: r.ok });
      if (r.subscriberCount != null) setData((d) => ({ ...d, subscriberCount: r.subscriberCount }));
    } catch (e: any) {
      setSubMsg({ text: `退订失败：${e.message}`, ok: false });
    } finally {
      setSubBusy(false);
    }
  };

  const total = data?.total || 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const acts: Activity[] = data?.activities || [];
  const warmIds = acts
    .filter((a) => a.hasImage)
    .slice(0, RUFFLE_PRELOAD_COUNT)
    .map((a) => a.activityId);
  const warmKey = warmIds.join(",");

  // 默认动态展示；Ruffle 与首屏 SWF 在空闲时后台预热，滚到播放器时尽量无感。
  useEffect(() => {
    if (!warmKey) return;
    warmRuffle(warmIds);
  }, [warmKey]);

  /** 分页条（顶部与底部各放一份）。 */
  const pager: ReactNode = total > PAGE_SIZE && (
    <div style={{ display: "flex", gap: 8, alignItems: "center", justifyContent: "center", padding: "6px 0" }}>
      <button disabled={page <= 0 || loading} onClick={() => (setPage(0), load(0, search))}>
        « 首页
      </button>
      <button disabled={page <= 0 || loading} onClick={() => (setPage(page - 1), load(page - 1, search))}>
        ‹ 上一页
      </button>
      <span style={{ fontSize: 13, color: "var(--muted)", minWidth: 70, textAlign: "center" }}>
        {page + 1} / {pages}
      </span>
      <button disabled={page >= pages - 1 || loading} onClick={() => (setPage(page + 1), load(page + 1, search))}>
        下一页 ›
      </button>
      <button
        disabled={page >= pages - 1 || loading}
        onClick={() => (setPage(pages - 1), load(pages - 1, search))}
      >
        末页 »
      </button>
    </div>
  );

  return (
    <div className="fade-up">
      <div className="card glow-card" style={{ padding: "16px 18px", marginBottom: 14 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 10 }}>
          <b style={{ fontSize: 16 }}>🔔 订阅最新活动通知</b>
          <span style={{ color: "var(--muted)", fontSize: 13 }}>
            {data?.subscriberCount != null ? `已有 ${data.subscriberCount} 人订阅` : ""}
          </span>
        </div>
        <div
          style={{
            fontSize: 13,
            color: "var(--warn)",
            background: "rgba(251,191,36,0.07)",
            border: "1px solid rgba(251,191,36,0.3)",
            borderRadius: 8,
            padding: "8px 12px",
            marginBottom: 12,
            lineHeight: 1.8,
          }}
        >
          订阅后，<b>一旦检测到游戏上线新活动</b>，我们就会第一时间把该活动的<b>长图</b>发到你的邮箱
          （每 1 分钟检测一次；只发真正的新活动，存量活动不打扰）。订阅成功会先发一封确认邮件。
        </div>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="你的邮箱"
            style={{ flex: 1, minWidth: 220 }}
          />
          <button className="btn-primary" onClick={subscribe} disabled={subBusy}>
            订阅
          </button>
          <button onClick={unsubscribe} disabled={subBusy}>
            退订
          </button>
        </div>
        {subMsg && (
          <div style={{ fontSize: 13, marginTop: 8, color: subMsg.ok ? "var(--accent)" : "var(--danger)" }}>
            {subMsg.text}
          </div>
        )}
        <div style={{ display: "flex", gap: 18, flexWrap: "wrap", fontSize: 12, color: "var(--muted)", marginTop: 8 }}>
          <span>
            🕒 最近活动抓取：<b>{data?.lastFetchAt ? fmtTime(data.lastFetchAt) : "—"}</b>
          </span>
          <span>
            🔄 下次检测活动：<b>{data?.nextDetectAt ? fmtTime(data.nextDetectAt) : "—"}</b>
          </span>
        </div>
      </div>

      {/* 关于本页说明（折叠，默认展开） */}
      <details
        open
        className="card"
        style={{ padding: "12px 16px", marginBottom: 14, fontSize: 13, lineHeight: 1.9, color: "var(--muted)" }}
      >
        <summary style={{ cursor: "pointer", fontWeight: 700, fontSize: 14, color: "var(--text)" }}>
          📖 关于「活动」· 说明
        </summary>
        <div style={{ marginTop: 8 }}>
          <p style={{ margin: "4px 0" }}>
            本页提供两种解析方式：<b>🎬 在线解析</b> 与 <b>🖼 长图解析</b>。
          </p>
          <ul style={{ margin: "4px 0", paddingLeft: 20 }}>
            <li>
              <b>在线解析</b>：基于 <b>Ruffle</b> 在浏览器里直接播放原版 SWF，可悬停、可交互；静态资源缓存由{" "}
              <b>Cloudflare Pro</b> 提供。
            </li>
            <li>
              <b>长图解析</b>：将活动渲染成一张长图，稳定、清晰、各端通用。
            </li>
          </ul>
          <p style={{ margin: "4px 0" }}>⚡ 在线解析偶有<b>闪烁</b>，属正常现象。</p>
          <p style={{ margin: "4px 0" }}>
            📱 手机端建议优先使用 <b>长图解析</b> —— 在线解析的移动端适配度有限。
          </p>
          <p style={{ margin: "4px 0" }}>
            🆓 本站 <b>0 盈利</b>，将来也不会盈利，因此不曾、也不会触碰任何一个人的利益。
          </p>
          <p style={{ margin: "4px 0" }}>
            ✨ 所有技术支持与原理均源自一位<b>高雅人士</b>，其技术之高，令我深为佩服。
          </p>
        </div>
      </details>

      {/* 工具栏：搜索 + 视图切换 */}
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 10 }}>
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && doSearch()}
          placeholder="🔍 搜活动名称"
          style={{ minWidth: 200 }}
        />
        <button onClick={doSearch}>搜索</button>
        <span style={{ color: "var(--muted)", fontSize: 13 }}>
          {total
            ? `共 ${total} 个活动${search ? `（搜“${search}”）` : "（新→旧）"}`
            : search
              ? `没有匹配“${search}”的活动`
              : ""}
        </span>
        <span style={{ marginLeft: "auto", display: "inline-flex", gap: 6 }}>
          <button
            className={view === "swf" ? "btn-primary" : ""}
            onClick={() => setView("swf")}
            title="Ruffle 在线播放原版 SWF，支持动画与交互。若活动原生提示异常，可切回长图。"
          >
            🎬 SWF 互动
          </button>
          <button className={view === "image" ? "btn-primary" : ""} onClick={() => setView("image")}>
            🖼 长图解析
          </button>
        </span>
      </div>

      {pager}

      {loading && (
        <div style={{ textAlign: "center", padding: 30 }}>
          <span className="spinner" />
        </div>
      )}
      {!loading && !acts.length && (
        <div className="card card-pad" style={{ textAlign: "center", color: "var(--muted)", padding: 40 }}>
          {data?.ok === false ? data.message || "活动抓取暂时失败，请稍后刷新" : "暂无活动"}
        </div>
      )}
      {!loading &&
        acts.map((a) => (
          <div key={a.activityId} className="card fade-up" style={{ padding: "16px 18px", marginBottom: 14 }}>
            <div style={{ fontWeight: 700, fontSize: 15 }}>🎮 {strip(a.title) || "活动"}</div>
            {(a.startTime || a.endTime) && (
              <div style={{ color: "var(--muted)", fontSize: 12, margin: "2px 0 2px" }}>
                ⏰ {a.startTime || ""} ~ {a.endTime || ""}
              </div>
            )}
            <div style={{ color: "var(--muted)", fontSize: 12, margin: "2px 0 8px" }}>
              🕒 获取时间：{a.firstSeenAt ? fmtTime(a.firstSeenAt) : "—"}
            </div>
            {strip(a.description) && (
              <div style={{ fontSize: 13, lineHeight: 1.6, color: "var(--muted)" }}>{strip(a.description)}</div>
            )}
            {a.hasImage &&
              (view === "image" ? (
                <ActImage id={a.activityId} onZoom={setZoom} />
              ) : (
                <SwfPlayer id={a.activityId} title={strip(a.title) || "活动"} />
              ))}
          </div>
        ))}

      {!loading && acts.length > 0 && pager}

      <Lightbox src={zoom} onClose={() => setZoom(null)} />
    </div>
  );
}
