"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { post } from "@/lib/api";
import { storeLogin, useAuth } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default function LoginPage() {
  const router = useRouter();
  const params = useSearchParams();
  const { refresh } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async () => {
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
      storeLogin(r.token);
      await refresh();
      router.replace(params.get("next") || "/player");
    } catch (e: any) {
      setMsg(`登录失败：${e.message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card glow-card fade-up" style={{ maxWidth: 420, margin: "48px auto", padding: "34px 30px" }}>
      <h1 style={{ margin: "0 0 4px", fontSize: 24 }} className="gradient-text">
        登录
      </h1>
      <p style={{ color: "var(--muted)", fontSize: 13, margin: "0 0 22px" }}>登录后可使用「玩家在线查询」</p>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="用户名"
          autoComplete="username"
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submit()}
          placeholder="密码"
          autoComplete="current-password"
        />
        <button className="btn-primary" onClick={submit} disabled={busy} style={{ padding: "10px 0" }}>
          {busy ? <span className="spinner" /> : "登录"}
        </button>
      </div>
      {msg && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 12 }}>{msg}</div>}
      <div style={{ marginTop: 18, fontSize: 13, color: "var(--muted)", display: "flex", justifyContent: "space-between" }}>
        <span>
          还没有账号？<Link href="/register">免费注册 →</Link>
        </span>
        <Link href="/forgot">忘记密码？</Link>
      </div>
    </div>
  );
}
