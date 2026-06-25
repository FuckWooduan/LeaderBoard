"use client";

/** 找回密码第二步（邮件落地页）：凭重置 token 设置新密码。 */

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { post } from "@/lib/api";

export const dynamic = "force-dynamic";

export default function ResetPage() {
  const params = useSearchParams();
  const token = params.get("token") || "";
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const submit = async () => {
    if (pw.length < 8 || pw.length > 72) {
      setMsg({ text: "新密码长度需在 8-72 位之间", ok: false });
      return;
    }
    if (pw !== pw2) {
      setMsg({ text: "两次输入的密码不一致", ok: false });
      return;
    }
    setBusy(true);
    setMsg(null);
    try {
      const r = await post("/api/auth/reset-password", { token, newPassword: pw });
      setMsg({ text: r.message || (r.ok ? "已重置" : "重置失败"), ok: r.ok });
      if (r.ok) setDone(true);
    } catch (e: any) {
      setMsg({ text: `重置失败：${e.message}`, ok: false });
    } finally {
      setBusy(false);
    }
  };

  if (!token) {
    return (
      <div className="card glow-card fade-up" style={{ maxWidth: 460, margin: "60px auto", padding: "40px 32px", textAlign: "center" }}>
        <div style={{ fontSize: 46, marginBottom: 12 }}>⚠️</div>
        <h1 style={{ fontSize: 22, margin: "0 0 8px" }}>链接无效</h1>
        <p style={{ color: "var(--muted)", fontSize: 14 }}>缺少重置 token，请从邮件里的链接进入本页。</p>
        <Link href="/forgot" className="btn btn-primary" style={{ marginTop: 14, display: "inline-block" }}>
          重新发起找回
        </Link>
      </div>
    );
  }

  return (
    <div className="card glow-card fade-up" style={{ maxWidth: 420, margin: "48px auto", padding: "34px 30px" }}>
      <h1 style={{ margin: "0 0 4px", fontSize: 24 }} className="gradient-text">
        设置新密码
      </h1>
      <p style={{ color: "var(--muted)", fontSize: 13, margin: "0 0 22px" }}>
        重置成功后所有设备会退出登录，请用新密码重新登录
      </p>
      {done ? (
        <div style={{ textAlign: "center" }}>
          <div style={{ fontSize: 40, marginBottom: 10 }}>✅</div>
          <div style={{ color: "var(--accent)", fontSize: 14, marginBottom: 16 }}>{msg?.text}</div>
          <Link href="/login" className="btn btn-primary">
            去登录
          </Link>
        </div>
      ) : (
        <>
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <input
              type="password"
              value={pw}
              onChange={(e) => setPw(e.target.value)}
              placeholder="新密码（8-72 位）"
              autoComplete="new-password"
            />
            <input
              type="password"
              value={pw2}
              onChange={(e) => setPw2(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && submit()}
              placeholder="再输一次新密码"
              autoComplete="new-password"
            />
            <button className="btn-primary" onClick={submit} disabled={busy} style={{ padding: "10px 0" }}>
              {busy ? <span className="spinner" /> : "确认重置"}
            </button>
          </div>
          {msg && (
            <div style={{ color: msg.ok ? "var(--accent)" : "var(--danger)", fontSize: 13, marginTop: 12 }}>
              {msg.text}
            </div>
          )}
        </>
      )}
    </div>
  );
}
