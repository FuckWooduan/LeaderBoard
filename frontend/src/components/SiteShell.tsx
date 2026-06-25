"use client";

/**
 * 主站外壳：跑马灯 + 欢迎条 + AppBar（访问统计/登录态/主题切换）+ 全局抓取进度 + Tab 导航 + Footer。
 * 所有 site 页面共用（admin 用 AdminShell）。
 */

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { api } from "@/lib/api";
import { initCaptcha } from "@/lib/captcha";
import { fmtNum } from "@/lib/format";
import { useAuth } from "@/lib/auth";
import { Lightbox } from "./Modal";

const TABS: [string, string][] = [
  ["/", "战力排行榜"],
  ["/exp", "经验排行榜"],
  ["/ladder", "天梯排行榜"],
  ["/team", "战队排行榜"],
  ["/player", "玩家查询"],
  ["/weapon", "武器查询"],
  ["/slice", "分区排行榜"],
  ["/hall", "频道大厅"],
  ["/activity", "活动"],
  ["/msg", "留言板"],
];

/** 旧版 hash 路由 → 新路径（兼容收藏夹/外链 /#player/16/xxx 等）。 */
function legacyHashRedirect(router: ReturnType<typeof useRouter>): boolean {
  const h = location.hash.replace(/^#/, "");
  if (!h) return false;
  const parts = h.split("/");
  const tab = parts[0];
  const map: Record<string, string> = {
    power: "/", exp: "/exp", ladder: "/ladder", team: "/team",
    player: "/player", weapon: "/weapon", slice: "/slice", hall: "/hall", activity: "/activity", msg: "/msg",
  };
  if (!(tab in map)) return false;
  let target = map[tab];
  const q = new URLSearchParams();
  if (tab === "player") {
    if (parts[1]) q.set("server", parts[1]);
    if (parts[2]) q.set("name", decodeURIComponent(parts[2]));
  } else if (tab === "weapon") {
    if (parts[1]) q.set("q", decodeURIComponent(parts[1]));
  } else if (tab === "slice") {
    if (parts[1]) q.set("key", parts[1]);
  } else if (tab === "hall") {
    // 旧 #hall/{区}/{频道id} → /hall?server=&channel=
    if (parts[1]) q.set("server", parts[1]);
    if (parts[2]) q.set("channel", parts[2]);
  } else if (parts[1]) {
    q.set("server", parts[1]);
  }
  const qs = q.toString();
  router.replace(qs ? `${target}?${qs}` : target);
  return true;
}

function Marquee({ onImage }: { onImage: (src: string) => void }) {
  const [data, setData] = useState<{ lines: string[]; image?: string } | null>(null);
  const trackRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    api("/api/public/marquee")
      .then((d) => {
        if (d?.enabled && ((d.lines || []).length || d.image)) setData({ lines: d.lines || [], image: d.image });
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (!data || !trackRef.current) {
      document.documentElement.style.setProperty("--marq-h", "0px");
      return;
    }
    const bar = trackRef.current.parentElement!;
    document.documentElement.style.setProperty("--marq-h", bar.offsetHeight + "px");
    const w = trackRef.current.scrollWidth / 2;
    trackRef.current.style.animationDuration = Math.max(12, Math.round(w / 80)) + "s";
  }, [data]);

  if (!data) return null;
  const seg = (k: string) => (
    <span key={k} style={{ display: "inline-flex", alignItems: "center" }}>
      {data.lines.map((t, i) => (
        <span key={i} style={{ display: "inline-flex", alignItems: "center" }}>
          <span className="marquee-item">{t}</span>
          {(i < data.lines.length - 1 || data.image) && <span className="marquee-item dot">·</span>}
        </span>
      ))}
      {data.image && (
        // eslint-disable-next-line @next/next/no-img-element
        <img className="marquee-img" src={data.image} alt="广告" onClick={() => onImage(data.image!)} />
      )}
    </span>
  );
  return (
    <div className="marquee-bar">
      <div ref={trackRef} className="marquee-track">
        {seg("a")}
        {seg("b")}
      </div>
    </div>
  );
}

function VisitStats() {
  const [html, setHtml] = useState<ReactNode>(null);
  useEffect(() => {
    api("/api/public/visit")
      .then((d) => {
        setHtml(
          <>
            👥 今日 <b>{fmtNum(d.today)}</b> · 累计 <b>{fmtNum(d.total)}</b>
            {d.apiCallsTotal != null && (
              <>
                {" "}· 🤖 接口调用 今日 <b>{fmtNum(d.apiCallsToday ?? 0)}</b> / 总共{" "}
                <b>{fmtNum(d.apiCallsTotal)}</b>
              </>
            )}
          </>
        );
      })
      .catch(() => {});
  }, []);
  return <span style={{ fontSize: 12.5, color: "var(--muted)" }}>{html}</span>;
}

function ThemeToggle() {
  const [light, setLight] = useState(false);
  useEffect(() => {
    setLight(document.documentElement.getAttribute("data-theme") === "light");
  }, []);
  const toggle = () => {
    const next = !light;
    setLight(next);
    if (next) document.documentElement.setAttribute("data-theme", "light");
    else document.documentElement.removeAttribute("data-theme");
    try {
      localStorage.setItem("skg_theme", next ? "light" : "dark");
    } catch {}
  };
  return (
    <button onClick={toggle} title="切换主题" style={{ padding: "5px 10px", fontSize: 14 }}>
      {light ? "🌙" : "☀️"}
    </button>
  );
}

function UserMenu() {
  const { user, ready, logout } = useAuth();
  if (!ready) return null;
  if (!user) {
    return (
      <span style={{ display: "inline-flex", gap: 8 }}>
        <Link href="/login" className="btn" style={{ padding: "5px 12px", fontSize: 13 }}>
          登录
        </Link>
        <Link href="/register" className="btn btn-primary" style={{ padding: "5px 12px", fontSize: 13 }}>
          注册
        </Link>
      </span>
    );
  }
  return (
    <span style={{ display: "inline-flex", gap: 8, alignItems: "center", fontSize: 13 }}>
      <Link href="/account" title="个人中心 / API Key">
        <span className="badge" style={{ background: "rgba(52,211,153,0.15)", color: "var(--accent)", cursor: "pointer" }}>
          {user.role === "ADMIN" ? "👑 " : "🎮 "}
          {user.username}
        </span>
      </Link>
      {user.role === "ADMIN" && (
        <Link href="/admin" style={{ fontSize: 13 }}>
          后台
        </Link>
      )}
      <button onClick={() => void logout()} style={{ padding: "4px 10px", fontSize: 12.5 }}>
        退出
      </button>
    </span>
  );
}

/** footer 的「强制刷新」：清缓存 + 更新 SW + 重载。 */
function forceRefresh() {
  const done = () => location.reload();
  const jobs: Promise<unknown>[] = [];
  if (window.caches) jobs.push(caches.keys().then((ks) => Promise.all(ks.map((k) => caches.delete(k)))));
  if ("serviceWorker" in navigator)
    jobs.push(navigator.serviceWorker.getRegistrations().then((rs) => Promise.all(rs.map((r) => r.update()))));
  Promise.all(jobs).then(done, done);
  setTimeout(done, 1500);
}

export function SiteShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [redirecting, setRedirecting] = useState(false);

  useEffect(() => {
    void initCaptcha();
  }, []);

  // 旧 hash 链接兼容（只在首页判定一次）
  useEffect(() => {
    if (pathname === "/" && location.hash) {
      if (legacyHashRedirect(router)) setRedirecting(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openImage = useCallback((src: string) => setLightboxSrc(src), []);

  return (
    <>
      <Marquee onImage={openImage} />
      <div
        style={{
          background: "linear-gradient(90deg, rgba(79,141,249,0.16), rgba(167,139,250,0.13), rgba(52,211,153,0.12))",
          borderBottom: "1px solid var(--border)",
          textAlign: "center",
          padding: "7px 12px",
          fontSize: 13.5,
        }}
      >
        🎯 欢迎来到 <b>《氪了就能赢》</b> —— 修仙一时爽，数值火葬场
      </div>

      <header
        style={{
          display: "flex",
          alignItems: "center",
          gap: 12,
          flexWrap: "wrap",
          maxWidth: 1280,
          margin: "0 auto",
          padding: "14px 18px 10px",
        }}
      >
        <Link href="/" style={{ display: "inline-flex", alignItems: "center", gap: 9, color: "var(--text)" }}>
          <span
            style={{
              display: "inline-flex",
              width: 32,
              height: 32,
              alignItems: "center",
              justifyContent: "center",
              borderRadius: 9,
              background: "linear-gradient(135deg, var(--primary), #7c6cf9)",
              color: "#fff",
              fontWeight: 800,
              boxShadow: "var(--glow)",
            }}
          >
            ▦
          </span>
          <b className="gradient-text" style={{ fontSize: 17 }}>
            氪佬修仙 · 排行榜
          </b>
        </Link>
        <VisitStats />
        <span style={{ marginLeft: "auto", display: "inline-flex", gap: 10, alignItems: "center" }}>
          <a href="/api-docs" style={{ fontSize: 13 }}>
            📘 API 文档
          </a>
          <ThemeToggle />
          <UserMenu />
        </span>
      </header>

      <nav
        style={{
          position: "sticky",
          top: "var(--marq-h)",
          zIndex: 30,
          background: "color-mix(in srgb, var(--bg) 82%, transparent)",
          backdropFilter: "blur(12px)",
          borderBottom: "1px solid var(--border)",
        }}
      >
        <div
          style={{
            display: "flex",
            gap: 2,
            overflowX: "auto",
            maxWidth: 1280,
            margin: "0 auto",
            padding: "0 12px",
          }}
        >
          {TABS.map(([href, label]) => {
            const active = pathname === href;
            return (
              <Link
                key={href}
                href={href}
                style={{
                  padding: "11px 15px",
                  whiteSpace: "nowrap",
                  fontSize: 14,
                  fontWeight: active ? 700 : 400,
                  color: active ? "var(--primary-strong)" : "var(--muted)",
                  borderBottom: active ? "2px solid var(--primary)" : "2px solid transparent",
                  transition: "color 0.15s ease",
                }}
              >
                {label}
              </Link>
            );
          })}
        </div>
      </nav>

      <main style={{ maxWidth: 1280, margin: "0 auto", padding: "18px 16px 0", minHeight: "60vh" }}>
        {redirecting ? null : children}
      </main>

      <footer
        style={{
          maxWidth: 980,
          margin: "44px auto 28px",
          padding: "20px 16px",
          borderTop: "1px solid var(--border)",
          color: "var(--muted)",
          fontSize: 13,
          lineHeight: 2,
          textAlign: "center",
        }}
      >
        <div style={{ fontSize: 15, fontWeight: 700, marginBottom: 8 }} className="gradient-text">
          🙏 本站纯免费公益 · 制作不易、成本高昂
        </div>
        <div>
          🌐 高带宽服务器 · 住宅代理成本高昂，全凭热爱维护
        </div>
        <div>
          ✨ 支持<b>离线模式</b>：甚至可以 <b>Ctrl+S</b> 保存本站、本地使用，改成你喜欢的样子
        </div>
        <div>
          🔌 全站数据均开放 <a href="/api-docs">API</a>，欢迎接入
        </div>
        <div style={{ marginTop: 10 }}>
          <button onClick={forceRefresh} style={{ fontSize: 13 }}>
            🔄 强制刷新（清缓存取最新）
          </button>
        </div>
      </footer>

      <Lightbox src={lightboxSrc} onClose={() => setLightboxSrc(null)} />
    </>
  );
}
