"use client";

/** 后台首页：登录（站长 TOTP / 管理员账号密码）+ 已登录时的导航卡片。 */

import Link from "next/link";
import { useEffect, useState } from "react";
import { adminHeaders, post } from "@/lib/api";
import { ADMIN_NAV, adminLogout } from "@/components/AdminShell";
import { storeLogin } from "@/lib/auth";

export default function AdminHome() {
  const [authed, setAuthed] = useState<boolean | null>(null);
  const [mode, setMode] = useState<"totp" | "account">("account");
  const [otp, setOtp] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const hasAny = localStorage.getItem("admin_token") || localStorage.getItem("user_token");
    if (!hasAny) {
      setAuthed(false);
      return;
    }
    // 登录态探测用「无副作用」的裸 fetch：绝不能用会在 401 时跳转/重载的封装，
    // 否则 token 失效时登录页会陷入「探测 401 → 跳 /admin → 重载 → 再探测」的无限刷新循环。
    fetch("/api/boards", { headers: adminHeaders() })
      .then((r) => {
        if (r.status === 401) {
          try {
            localStorage.removeItem("admin_token");
          } catch {}
        }
        setAuthed(r.ok);
      })
      .catch(() => setAuthed(false));
  }, []);

  const loginTotp = async () => {
    if (otp.trim().length !== 6) {
      setMsg("请输入 6 位动态码");
      return;
    }
    setBusy(true);
    setMsg("");
    try {
      const r = await fetch("/api/admin/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ otp: otp.trim() }),
      });
      if (!r.ok) {
        setMsg("动态码错误");
        return;
      }
      const d = await r.json();
      localStorage.setItem("admin_token", d.token);
      setAuthed(true);
    } catch (e: any) {
      setMsg(`登录失败：${e.message}`);
    } finally {
      setBusy(false);
    }
  };

  const loginAccount = async () => {
    if (!username.trim() || !password) {
      setMsg("请填写用户名和密码");
      return;
    }
    setBusy(true);
    setMsg("");
    try {
      const r = await post("/api/auth/login", { username: username.trim(), password });
      if (!r.ok) {
        setMsg(r.message || "登录失败");
        return;
      }
      if (r.role !== "ADMIN") {
        setMsg("该账号不是管理员（管理员需邀请码注册）");
        return;
      }
      storeLogin(r.token);
      setAuthed(true);
    } catch (e: any) {
      setMsg(`登录失败：${e.message}`);
    } finally {
      setBusy(false);
    }
  };

  if (authed === null) {
    return (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", minHeight: "60dvh" }}>
        <span className="spinner" />
      </div>
    );
  }

  if (!authed) {
    return (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", minHeight: "84dvh", padding: 18 }}>
        <div className="card glow-card fade-up" style={{ maxWidth: 400, width: "100%", padding: "32px 30px" }}>
          <h1 style={{ margin: "0 0 4px", fontSize: 22 }}>
            ⚙ <span className="gradient-text">StrikeGod 后台</span>
          </h1>
          <p style={{ color: "var(--muted)", fontSize: 13, margin: "0 0 18px" }}>仅受邀管理员可进入</p>
          <div style={{ display: "flex", gap: 6, marginBottom: 16 }}>
            <button
              className={mode === "account" ? "btn-primary" : ""}
              style={{ flex: 1 }}
              onClick={() => setMode("account")}
            >
              管理员账号
            </button>
            <button className={mode === "totp" ? "btn-primary" : ""} style={{ flex: 1 }} onClick={() => setMode("totp")}>
              动态码
            </button>
          </div>
          {mode === "totp" ? (
            <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <input
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, "").slice(0, 6))}
                onKeyDown={(e) => e.key === "Enter" && loginTotp()}
                placeholder="6 位动态安全码（TOTP）"
                inputMode="numeric"
                style={{ textAlign: "center", fontSize: 18, letterSpacing: 6 }}
              />
              <button className="btn-primary" onClick={loginTotp} disabled={busy} style={{ padding: "10px 0" }}>
                {busy ? <span className="spinner" /> : "登录"}
              </button>
            </div>
          ) : (
            <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="管理员用户名"
                autoComplete="username"
              />
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && loginAccount()}
                placeholder="密码"
                autoComplete="current-password"
              />
              <button className="btn-primary" onClick={loginAccount} disabled={busy} style={{ padding: "10px 0" }}>
                {busy ? <span className="spinner" /> : "登录"}
              </button>
            </div>
          )}
          {msg && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 12 }}>{msg}</div>}
          <div style={{ marginTop: 16, fontSize: 12.5, color: "var(--muted)" }}>
            <Link href="/">← 回前台</Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div style={{ maxWidth: 980, margin: "0 auto", padding: "26px 16px" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 22, flexWrap: "wrap" }}>
        <h1 style={{ fontSize: 22, margin: 0 }}>
          ⚙ <span className="gradient-text">StrikeGod 后台</span>
        </h1>
        <span style={{ marginLeft: "auto", display: "inline-flex", gap: 10 }}>
          <Link href="/" style={{ fontSize: 13 }}>
            ← 回前台
          </Link>
          <button onClick={adminLogout} style={{ padding: "5px 12px", fontSize: 13 }}>
            退出登录
          </button>
        </span>
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(230px, 1fr))", gap: 14 }}>
        {ADMIN_NAV.map(([href, icon, label]) => (
          <Link key={href} href={href} className="card glow-card fade-up" style={{ padding: "22px 20px", color: "var(--text)" }}>
            <div style={{ fontSize: 30 }}>{icon}</div>
            <div style={{ fontWeight: 700, marginTop: 8 }}>{label}</div>
          </Link>
        ))}
      </div>
    </div>
  );
}
