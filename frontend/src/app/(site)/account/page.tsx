"use client";

/** 个人中心：账号信息 + 账号安全（查看/换绑邮箱、改密码）+ 自助 API Key（生成/重置/复制 + 用法说明）。 */

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, post } from "@/lib/api";
import { fmtFullTime, fmtNum } from "@/lib/format";
import { useAuth } from "@/lib/auth";

export const dynamic = "force-dynamic";

interface MyKey {
  exists: boolean;
  key?: string;
  active?: boolean;
  createdAt?: number;
  callCount?: number;
}

export default function AccountPage() {
  const { user, ready, logout, refresh } = useAuth();
  const [myKey, setMyKey] = useState<MyKey | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  // ── 账号安全 ──
  const [pwOld, setPwOld] = useState("");
  const [pwNew, setPwNew] = useState("");
  const [pwNew2, setPwNew2] = useState("");
  const [pwBusy, setPwBusy] = useState(false);
  const [pwMsg, setPwMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [emPw, setEmPw] = useState("");
  const [emNew, setEmNew] = useState("");
  const [emBusy, setEmBusy] = useState(false);
  const [emMsg, setEmMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [resendBusy, setResendBusy] = useState(false);

  const changePassword = async () => {
    if (pwNew.length < 8 || pwNew.length > 72) {
      setPwMsg({ text: "新密码长度需在 8-72 位之间", ok: false });
      return;
    }
    if (pwNew !== pwNew2) {
      setPwMsg({ text: "两次输入的新密码不一致", ok: false });
      return;
    }
    setPwBusy(true);
    setPwMsg(null);
    try {
      const r = await post("/api/auth/change-password", { oldPassword: pwOld, newPassword: pwNew });
      setPwMsg({ text: r.message || (r.ok ? "密码已修改" : "修改失败"), ok: r.ok });
      if (r.ok) {
        setPwOld("");
        setPwNew("");
        setPwNew2("");
      }
    } catch (e: any) {
      setPwMsg({ text: `修改失败：${e.message}`, ok: false });
    } finally {
      setPwBusy(false);
    }
  };

  const changeEmail = async () => {
    if (!emNew.includes("@")) {
      setEmMsg({ text: "请填写有效的新邮箱", ok: false });
      return;
    }
    setEmBusy(true);
    setEmMsg(null);
    try {
      const r = await post("/api/auth/change-email", { password: emPw, email: emNew.trim() });
      setEmMsg({ text: r.message || (r.ok ? "邮箱已更换" : "更换失败"), ok: r.ok });
      if (r.ok) {
        setEmPw("");
        setEmNew("");
        await refresh(); // 同步新邮箱与未验证状态
      }
    } catch (e: any) {
      setEmMsg({ text: `更换失败：${e.message}`, ok: false });
    } finally {
      setEmBusy(false);
    }
  };

  // ── 会话与危险操作 ──
  const [othersBusy, setOthersBusy] = useState(false);
  const [delPw, setDelPw] = useState("");
  const [delBusy, setDelBusy] = useState(false);
  const [dangerMsg, setDangerMsg] = useState<{ text: string; ok: boolean } | null>(null);

  const logoutOthers = async () => {
    setOthersBusy(true);
    setDangerMsg(null);
    try {
      const r = await post("/api/auth/logout-others");
      setDangerMsg({ text: r.message || (r.ok ? "其他设备已退出" : "操作失败"), ok: r.ok });
    } catch (e: any) {
      setDangerMsg({ text: `操作失败：${e.message}`, ok: false });
    } finally {
      setOthersBusy(false);
    }
  };

  const deleteAccount = async () => {
    if (!delPw) {
      setDangerMsg({ text: "请输入密码确认", ok: false });
      return;
    }
    if (!confirm("确认注销账号？注销后将立即退出登录，账号停用（可联系管理员恢复）。")) return;
    setDelBusy(true);
    setDangerMsg(null);
    try {
      const r = await post("/api/auth/delete-account", { password: delPw });
      setDangerMsg({ text: r.message || (r.ok ? "已注销" : "注销失败"), ok: r.ok });
      if (r.ok) {
        localStorage.removeItem("user_token");
        setTimeout(() => (location.href = "/"), 1500);
      }
    } catch (e: any) {
      setDangerMsg({ text: `注销失败：${e.message}`, ok: false });
    } finally {
      setDelBusy(false);
    }
  };

  const resendVerify = async () => {
    setResendBusy(true);
    try {
      const r = await post("/api/auth/resend-verify");
      setEmMsg({ text: r.message || (r.ok ? "验证邮件已发送" : "发送失败"), ok: r.ok });
    } catch (e: any) {
      setEmMsg({ text: `发送失败：${e.message}`, ok: false });
    } finally {
      setResendBusy(false);
    }
  };

  useEffect(() => {
    if (ready && user) {
      api("/api/user/apikey")
        .then(setMyKey)
        .catch(() => {});
    }
  }, [ready, user]);

  const reset = async () => {
    if (
      myKey?.exists &&
      !confirm("重置后旧 Key 立即失效，所有使用旧 Key 的程序需要换新。确认重置？")
    ) {
      return;
    }
    setBusy(true);
    setMsg(null);
    try {
      const r = await post("/api/user/apikey/reset");
      if (r.ok) {
        setMsg({ text: "已生成新 Key，请妥善保存", ok: true });
        const d = await api("/api/user/apikey");
        setMyKey(d);
      } else {
        setMsg({ text: r.message || "生成失败", ok: false });
      }
    } catch (e: any) {
      setMsg({ text: `生成失败：${e.message}`, ok: false });
    } finally {
      setBusy(false);
    }
  };

  const copy = () => {
    if (myKey?.key) {
      navigator.clipboard?.writeText(myKey.key).then(() => setMsg({ text: "已复制到剪贴板", ok: true }));
    }
  };

  if (ready && !user) {
    return (
      <div className="card glow-card fade-up" style={{ maxWidth: 480, margin: "48px auto", padding: "36px 32px", textAlign: "center" }}>
        <div style={{ fontSize: 40, marginBottom: 10 }}>👤</div>
        <h1 style={{ fontSize: 20, margin: "0 0 8px" }}>个人中心需要登录</h1>
        <div style={{ display: "flex", gap: 10, justifyContent: "center", marginTop: 16 }}>
          <Link href="/login" className="btn">
            登录
          </Link>
          <Link href="/register" className="btn btn-primary">
            免费注册
          </Link>
        </div>
      </div>
    );
  }
  if (!user) return null;

  return (
    <div className="fade-up" style={{ maxWidth: 760, margin: "0 auto" }}>
      <div className="card glow-card" style={{ padding: "20px 22px", marginBottom: 16 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <div
            style={{
              width: 46,
              height: 46,
              borderRadius: "50%",
              background: "linear-gradient(135deg, var(--primary), #7c6cf9)",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: 20,
              color: "#fff",
              fontWeight: 800,
            }}
          >
            {user.username.slice(0, 1).toUpperCase()}
          </div>
          <div>
            <div style={{ fontWeight: 700, fontSize: 17 }}>
              {user.username}{" "}
              {user.role === "ADMIN" && (
                <span className="badge" style={{ background: "rgba(244,63,94,0.13)", color: "#fb7185" }}>
                  👑 管理员
                </span>
              )}
            </div>
            <div style={{ color: "var(--muted)", fontSize: 13 }}>
              example.com 站点账号
              {user.createdAt ? ` · 注册于 ${fmtFullTime(user.createdAt)}` : ""}
              {user.lastLoginAt ? ` · 上次登录 ${fmtFullTime(user.lastLoginAt)}` : ""}
            </div>
          </div>
          <span style={{ marginLeft: "auto", display: "inline-flex", gap: 10 }}>
            {user.role === "ADMIN" && (
              <Link href="/admin" className="btn" style={{ fontSize: 13 }}>
                进入后台
              </Link>
            )}
            <button onClick={() => void logout().then(() => (location.href = "/"))} style={{ fontSize: 13 }}>
              退出登录
            </button>
          </span>
        </div>
      </div>

      <div className="card" style={{ padding: "20px 22px", marginBottom: 16 }}>
        <div style={{ fontWeight: 700, fontSize: 16, marginBottom: 6 }}>🛡️ 账号安全</div>

        {/* 绑定邮箱 */}
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", margin: "10px 0 16px" }}>
          <span style={{ color: "var(--muted)", fontSize: 13 }}>绑定邮箱</span>
          <b style={{ fontSize: 14 }}>{user.email || "未绑定（绑定后才能用留言板和找回密码）"}</b>
          {user.email &&
            (user.emailVerified ? (
              <span className="badge" style={{ background: "rgba(52,211,153,0.13)", color: "var(--accent)" }}>
                ✓ 已验证
              </span>
            ) : (
              <>
                <span className="badge" style={{ background: "rgba(251,191,36,0.13)", color: "var(--warn)" }}>
                  待验证
                </span>
                <button style={{ padding: "4px 10px", fontSize: 12 }} onClick={resendVerify} disabled={resendBusy}>
                  {resendBusy ? "发送中…" : "重发验证邮件"}
                </button>
              </>
            ))}
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", gap: 18 }}>
          {/* 改密码 */}
          <div style={{ border: "1px solid var(--border)", borderRadius: 10, padding: "14px 16px" }}>
            <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 10 }}>🔒 修改密码</div>
            <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
              <input
                type="password"
                value={pwOld}
                onChange={(e) => setPwOld(e.target.value)}
                placeholder="当前密码"
                autoComplete="current-password"
              />
              <input
                type="password"
                value={pwNew}
                onChange={(e) => setPwNew(e.target.value)}
                placeholder="新密码（8-72 位）"
                autoComplete="new-password"
              />
              <input
                type="password"
                value={pwNew2}
                onChange={(e) => setPwNew2(e.target.value)}
                placeholder="再输一次新密码"
                autoComplete="new-password"
              />
              <button className="btn-primary" onClick={changePassword} disabled={pwBusy || !pwOld || !pwNew}>
                {pwBusy ? <span className="spinner" /> : "确认修改"}
              </button>
            </div>
            <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 8 }}>修改成功后，其他已登录设备会被退出。</div>
            {pwMsg && (
              <div style={{ marginTop: 8, fontSize: 13, color: pwMsg.ok ? "var(--accent)" : "var(--danger)" }}>
                {pwMsg.text}
              </div>
            )}
          </div>

          {/* 绑定/换绑邮箱 */}
          <div style={{ border: "1px solid var(--border)", borderRadius: 10, padding: "14px 16px" }}>
            <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 10 }}>
              ✉️ {user.email ? "更换邮箱" : "绑定邮箱"}
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
              <input
                type="password"
                value={emPw}
                onChange={(e) => setEmPw(e.target.value)}
                placeholder="登录密码（验证身份）"
                autoComplete="current-password"
              />
              <input
                type="email"
                value={emNew}
                onChange={(e) => setEmNew(e.target.value)}
                placeholder="新邮箱"
                autoComplete="email"
              />
              <button className="btn-primary" onClick={changeEmail} disabled={emBusy || !emPw || !emNew}>
                {emBusy ? <span className="spinner" /> : "确认更换"}
              </button>
            </div>
            <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 8 }}>
              更换后需到新邮箱点击验证链接重新完成验证。
            </div>
            {emMsg && (
              <div style={{ marginTop: 8, fontSize: 13, color: emMsg.ok ? "var(--accent)" : "var(--danger)" }}>
                {emMsg.text}
              </div>
            )}
          </div>
        </div>

        {/* 会话与危险操作 */}
        <div style={{ marginTop: 18, borderTop: "1px solid var(--border)", paddingTop: 14 }}>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
            <button onClick={logoutOthers} disabled={othersBusy}>
              {othersBusy ? "处理中…" : "🚪 退出其他全部设备"}
            </button>
            <span style={{ color: "var(--muted)", fontSize: 12.5 }}>怀疑账号在别处登录时使用，当前设备不受影响</span>
          </div>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center", marginTop: 12 }}>
            <input
              type="password"
              value={delPw}
              onChange={(e) => setDelPw(e.target.value)}
              placeholder="输入密码以注销账号"
              autoComplete="current-password"
              style={{ maxWidth: 220 }}
            />
            <button className="btn-danger" onClick={deleteAccount} disabled={delBusy || !delPw}>
              {delBusy ? <span className="spinner" /> : "⚠️ 注销账号"}
            </button>
            <span style={{ color: "var(--muted)", fontSize: 12.5 }}>注销=停用并退出登录，可联系管理员恢复</span>
          </div>
          {dangerMsg && (
            <div style={{ marginTop: 10, fontSize: 13, color: dangerMsg.ok ? "var(--accent)" : "var(--danger)" }}>
              {dangerMsg.text}
            </div>
          )}
        </div>
      </div>

      <div className="card" style={{ padding: "20px 22px" }}>
        <div style={{ fontWeight: 700, fontSize: 16, marginBottom: 6 }}>🔑 我的 API Key</div>
        <div style={{ color: "var(--muted)", fontSize: 13, lineHeight: 1.8, marginBottom: 14 }}>
          每个账号可生成 <b>1 个</b> API Key，用于<b>程序化调用</b>需要登录/人机验证的查询接口
          （等同你的登录态、免人机验证）。请妥善保管，泄露可随时在此重置。
        </div>

        {myKey?.exists ? (
          <>
            <div
              style={{
                fontFamily: "monospace",
                fontSize: 13.5,
                padding: "10px 14px",
                background: "var(--bg-soft)",
                border: "1px solid var(--border-strong)",
                borderRadius: 10,
                wordBreak: "break-all",
                marginBottom: 10,
              }}
            >
              {myKey.key}
            </div>
            <div style={{ display: "flex", gap: 14, flexWrap: "wrap", fontSize: 12.5, color: "var(--muted)", marginBottom: 12 }}>
              <span>创建于 {fmtFullTime(myKey.createdAt)}</span>
              <span>累计调用 {fmtNum(myKey.callCount || 0)} 次</span>
            </div>
            <div style={{ display: "flex", gap: 8 }}>
              <button onClick={copy}>📋 复制</button>
              <button className="btn-danger" onClick={reset} disabled={busy}>
                ♻️ 重置 Key
              </button>
            </div>
          </>
        ) : (
          <button className="btn-primary" onClick={reset} disabled={busy}>
            {busy ? <span className="spinner" /> : "＋ 生成我的 API Key"}
          </button>
        )}
        {msg && (
          <div style={{ marginTop: 10, fontSize: 13, color: msg.ok ? "var(--accent)" : "var(--danger)" }}>{msg.text}</div>
        )}

        <div style={{ marginTop: 18, borderTop: "1px solid var(--border)", paddingTop: 14 }}>
          <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 8 }}>用法（请求头带 Key）</div>
          <pre
            style={{
              background: "var(--bg-soft)",
              border: "1px solid var(--border)",
              borderRadius: 10,
              padding: "12px 14px",
              fontSize: 12.5,
              overflowX: "auto",
              lineHeight: 1.8,
            }}
          >
            {`# 玩家在线查询（server 用游戏内看到的服号 districtId）
curl "https://example.com/api/key/player?name=玩家名&server=16" \\
  -H "X-API-Key: 你的Key"

# 在线玩家位置（频道/房间；charId 来自上一步返回的 characterId）
curl "https://example.com/api/key/player/location?server=16&charId=12345" \\
  -H "X-API-Key: 你的Key"`}
          </pre>
          <div style={{ fontSize: 12.5, color: "var(--muted)" }}>
            也支持 <code>Authorization: Bearer 你的Key</code>。完整说明见{" "}
            <a href="/api-docs" target="_blank">
              API 文档
            </a>
            。
          </div>
        </div>
      </div>
    </div>
  );
}
