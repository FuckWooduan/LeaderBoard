"use client";

import Link from "next/link";
import { useState } from "react";
import { post } from "@/lib/api";
import { storeLogin, useAuth } from "@/lib/auth";
import { withCaptcha } from "@/lib/captcha";

export default function RegisterClient() {
  const { refresh } = useAuth();
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [password2, setPassword2] = useState("");
  const [inviteCode, setInviteCode] = useState("");
  const [showInvite, setShowInvite] = useState(false);
  const [msg, setMsg] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!username.trim()) {
      setMsg("请填写用户名（2-24 位，中文/字母/数字/下划线）");
      return;
    }
    if (!email.includes("@")) {
      setMsg("请填写有效邮箱（需邮箱验证后才能发留言）");
      return;
    }
    if (password.length < 8) {
      setMsg("密码至少 8 位");
      return;
    }
    if (password !== password2) {
      setMsg("两次输入的密码不一致");
      return;
    }
    setBusy(true);
    setMsg("");
    try {
      const gt = await withCaptcha();
      const r = await post("/api/auth/register", {
        username: username.trim(),
        email: email.trim(),
        password,
        inviteCode: inviteCode.trim(),
        ...gt,
      });
      if (!r.ok) {
        setMsg(r.message || "注册失败");
        return;
      }
      storeLogin(r.token);
      await refresh();
      setDone(email.trim());
    } catch (e: any) {
      setMsg(e.message || "注册失败");
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <div className="card glow-card fade-up" style={{ maxWidth: 460, margin: "48px auto", padding: "36px 32px", textAlign: "center" }}>
        <div style={{ fontSize: 44, marginBottom: 10 }}>✉️</div>
        <h1 style={{ fontSize: 22, margin: "0 0 8px" }}>注册成功，请验证邮箱</h1>
        <p style={{ color: "var(--muted)", fontSize: 14, lineHeight: 1.8 }}>
          已向 <b>{done}</b> 发送验证邮件，请点击邮件里的链接完成验证。
          <br />
          验证后即可在留言板发言；玩家查询等功能现在就能用。
        </p>
        <div style={{ display: "flex", gap: 10, justifyContent: "center", marginTop: 18 }}>
          <Link href="/player" className="btn btn-primary">
            去玩家查询
          </Link>
          <Link href="/" className="btn">
            回首页
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="card glow-card fade-up" style={{ maxWidth: 420, margin: "48px auto", padding: "34px 30px" }}>
      <h1 style={{ margin: "0 0 4px", fontSize: 24 }} className="gradient-text">
        注册
      </h1>
      <p style={{ color: "var(--muted)", fontSize: 13, margin: "0 0 22px" }}>
        免费注册，登录后即可使用「玩家在线查询」「留言板发言（需邮箱验证）」
      </p>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="邮箱（用于验证和通知）"
          autoComplete="email"
        />
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="用户名（2-24 位）"
          autoComplete="username"
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="密码（至少 8 位）"
          autoComplete="new-password"
        />
        <input
          type="password"
          value={password2}
          onChange={(e) => setPassword2(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submit()}
          placeholder="确认密码"
          autoComplete="new-password"
        />
        {showInvite ? (
          <input
            value={inviteCode}
            onChange={(e) => setInviteCode(e.target.value)}
            placeholder="管理员邀请码（inv_ 开头）"
          />
        ) : (
          <button
            onClick={() => setShowInvite(true)}
            style={{ border: "none", background: "none", color: "var(--muted)", fontSize: 12, textAlign: "left", padding: 0 }}
          >
            有管理员邀请码？点此填写
          </button>
        )}
        <button className="btn-primary" onClick={submit} disabled={busy} style={{ padding: "10px 0" }}>
          {busy ? <span className="spinner" /> : "注册"}
        </button>
      </div>
      {msg && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 12 }}>{msg}</div>}
      <div style={{ marginTop: 18, fontSize: 13, color: "var(--muted)" }}>
        已有账号？<Link href="/login">去登录 →</Link>
      </div>
    </div>
  );
}
