"use client";

/** 找回密码第一步：输入用户名 → 发重置邮件到绑定邮箱。 */

import Link from "next/link";
import { useState } from "react";
import { post } from "@/lib/api";
import { withCaptcha } from "@/lib/captcha";

export const dynamic = "force-dynamic";

export default function ForgotPage() {
  const [username, setUsername] = useState("");
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);

  const submit = async () => {
    if (!username.trim()) {
      setMsg({ text: "请填写用户名", ok: false });
      return;
    }
    setBusy(true);
    setMsg(null);
    try {
      const gt = await withCaptcha();
      const r = await post("/api/auth/forgot", { username: username.trim(), ...gt });
      setMsg({ text: r.message || (r.ok ? "已发送" : "发送失败"), ok: r.ok });
      if (r.ok) setSent(true);
    } catch (e: any) {
      setMsg({ text: `发送失败：${e.message}`, ok: false });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card glow-card fade-up" style={{ maxWidth: 420, margin: "48px auto", padding: "34px 30px" }}>
      <h1 style={{ margin: "0 0 4px", fontSize: 24 }} className="gradient-text">
        找回密码
      </h1>
      <p style={{ color: "var(--muted)", fontSize: 13, margin: "0 0 22px" }}>
        输入用户名，我们会把重置链接发到该账号<b>绑定的邮箱</b>（30 分钟内有效）
      </p>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submit()}
          placeholder="用户名"
          autoComplete="username"
          disabled={sent}
        />
        <button className="btn-primary" onClick={submit} disabled={busy || sent} style={{ padding: "10px 0" }}>
          {busy ? <span className="spinner" /> : sent ? "已发送，请查收邮箱" : "发送重置邮件"}
        </button>
      </div>
      {msg && (
        <div style={{ color: msg.ok ? "var(--accent)" : "var(--danger)", fontSize: 13, marginTop: 12 }}>{msg.text}</div>
      )}
      <div style={{ marginTop: 18, fontSize: 13, color: "var(--muted)" }}>
        想起密码了？<Link href="/login">去登录 →</Link>
      </div>
    </div>
  );
}
