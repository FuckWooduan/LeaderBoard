"use client";

/** 后台外壳：鉴权守卫（无 token → /admin 登录页）+ 顶栏 + 导航。 */

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { adminHeaders } from "@/lib/api";

export const ADMIN_NAV: [string, string, string][] = [
  ["/admin/boards", "📊", "榜单管理"],
  ["/admin/fields", "🧩", "字段配置"],
  ["/admin/accounts", "🎮", "账号管理"],
  ["/admin/scout", "🛰️", "侦察号"],
  ["/admin/proxy", "🌐", "代理节点"],
  ["/admin/messages", "💬", "留言板"],
  ["/admin/users", "👥", "用户与邀请"],
  ["/admin/apikey", "🔑", "API Key"],
];

export function adminLogout() {
  localStorage.removeItem("admin_token");
  localStorage.removeItem("user_token");
  location.href = "/admin";
}

/**
 * 校验当前是否已有有效后台会话（任一 token 通过 /api/boards 探测）。
 * 探测必须用无副作用的裸 fetch（adminApi 在 401 时会整页跳转，与本守卫的 router.replace 竞态，
 * 且若用于登录页会造成无限刷新）。失效跳转统一由调用方（AdminShell）的 router.replace 完成。
 */
export function useAdminGuard() {
  const [state, setState] = useState<"checking" | "ok" | "no">("checking");
  useEffect(() => {
    const hasAny = localStorage.getItem("admin_token") || localStorage.getItem("user_token");
    if (!hasAny) {
      setState("no");
      return;
    }
    fetch("/api/boards", { headers: adminHeaders() })
      .then((r) => {
        if (r.status === 401) {
          try {
            localStorage.removeItem("admin_token");
          } catch {}
        }
        setState(r.ok ? "ok" : "no");
      })
      .catch(() => setState("no"));
  }, []);
  return state;
}

export function AdminShell({ children, title }: { children: ReactNode; title: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const guard = useAdminGuard();

  useEffect(() => {
    if (guard === "no") router.replace("/admin");
  }, [guard, router]);

  if (guard !== "ok") {
    return (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", minHeight: "60dvh" }}>
        <span className="spinner" />
      </div>
    );
  }

  return (
    <div style={{ maxWidth: 1280, margin: "0 auto", padding: "0 16px 40px" }}>
      <header
        style={{
          display: "flex",
          alignItems: "center",
          gap: 14,
          flexWrap: "wrap",
          padding: "16px 2px 12px",
          borderBottom: "1px solid var(--border)",
          marginBottom: 16,
        }}
      >
        <Link href="/admin" style={{ display: "inline-flex", alignItems: "center", gap: 8, color: "var(--text)" }}>
          <span
            style={{
              display: "inline-flex",
              width: 30,
              height: 30,
              alignItems: "center",
              justifyContent: "center",
              borderRadius: 8,
              background: "linear-gradient(135deg, #f43f5e, #d946ef)",
              color: "#fff",
              fontWeight: 800,
            }}
          >
            ⚙
          </span>
          <b>StrikeGod 后台</b>
        </Link>
        <nav style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
          {ADMIN_NAV.map(([href, icon, label]) => (
            <Link
              key={href}
              href={href}
              className="btn"
              style={{
                padding: "5px 11px",
                fontSize: 13,
                borderColor: pathname === href ? "var(--primary)" : "var(--border)",
                color: pathname === href ? "var(--primary-strong)" : "var(--muted)",
              }}
            >
              {icon} {label}
            </Link>
          ))}
        </nav>
        <span style={{ marginLeft: "auto", display: "inline-flex", gap: 10, alignItems: "center" }}>
          <Link href="/" style={{ fontSize: 13 }}>
            ← 回前台
          </Link>
          <button onClick={adminLogout} style={{ padding: "5px 12px", fontSize: 13 }}>
            退出登录
          </button>
        </span>
      </header>
      <h1 style={{ fontSize: 20, margin: "0 0 16px" }}>{title}</h1>
      {children}
    </div>
  );
}

/** 底部浮动 toast（与旧版 Admin.toast 等价）。 */
export function useToast(): [ReactNode, (msg: string, ok?: boolean) => void] {
  const [toast, setToast] = useState<{ msg: string; ok: boolean } | null>(null);
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2000);
    return () => clearTimeout(t);
  }, [toast]);
  const node = toast ? (
    <div
      style={{
        position: "fixed",
        left: "50%",
        bottom: 28,
        transform: "translateX(-50%)",
        zIndex: 200,
        background: toast.ok ? "var(--surface-strong)" : "#7f1d1d",
        color: toast.ok ? "var(--text)" : "#fff",
        border: "1px solid var(--border-strong)",
        borderRadius: 10,
        padding: "9px 18px",
        fontSize: 13.5,
        boxShadow: "var(--shadow)",
      }}
    >
      {toast.msg}
    </div>
  ) : null;
  return [node, (msg, ok = true) => setToast({ msg, ok })];
}
