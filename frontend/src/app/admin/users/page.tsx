"use client";

/** 后台 · 用户与邀请：站点账号列表（停用/启用）+ 管理员邀请码（签发/撤销）。 */

import { useCallback, useEffect, useState } from "react";
import { adminApi, adminPost } from "@/lib/api";
import { fmtFullTime } from "@/lib/format";
import { AdminShell, useToast } from "@/components/AdminShell";

interface User {
  id: number;
  username: string;
  role: string;
  invitedBy?: string;
  createdAt: number;
  lastLoginAt: number;
  disabled: boolean;
}

interface Invite {
  code: string;
  role: string;
  note?: string;
  createdBy?: string;
  createdAt: number;
  expiresAt: number;
  usedBy?: string;
  usedAt: number;
  revoked: boolean;
}

function inviteStatus(i: Invite): [string, string] {
  if (i.revoked) return ["已撤销", "var(--muted)"];
  if (i.usedBy) return [`已使用（${i.usedBy}）`, "var(--accent)"];
  if (i.expiresAt <= Date.now()) return ["已过期", "var(--warn)"];
  return ["待使用", "var(--primary-strong)"];
}

export default function AdminUsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [invites, setInvites] = useState<Invite[]>([]);
  const [note, setNote] = useState("");
  const [newCode, setNewCode] = useState("");
  const [toastNode, toast] = useToast();

  const load = useCallback(() => {
    adminApi("/api/boards/users/list")
      .then((d) => setUsers(d.users || []))
      .catch(() => {});
    adminApi("/api/boards/invites/list")
      .then((d) => setInvites(d.invites || []))
      .catch(() => {});
  }, []);

  useEffect(load, [load]);

  const createInvite = async () => {
    try {
      const r = await adminPost("/api/boards/invites/create", { note: note.trim() });
      setNewCode(r.code || "");
      setNote("");
      toast("邀请码已生成");
      load();
    } catch (e: any) {
      toast(`生成失败：${e.message}`, false);
    }
  };

  const revoke = async (code: string) => {
    if (!confirm(`确认撤销邀请码 ${code}？`)) return;
    try {
      await adminPost("/api/boards/invites/revoke", { code });
      toast("已撤销");
      load();
    } catch (e: any) {
      toast(`撤销失败：${e.message}`, false);
    }
  };

  const setDisabled = async (u: User, disabled: boolean) => {
    if (disabled && !confirm(`确认停用「${u.username}」？其会话将无法继续操作。`)) return;
    try {
      const r = await adminPost("/api/boards/users/disable", { id: u.id, disabled });
      if (r.ok) {
        toast(disabled ? "已停用" : "已启用");
        load();
      } else {
        toast(r.message || "操作失败", false);
      }
    } catch (e: any) {
      toast(`操作失败：${e.message}`, false);
    }
  };

  return (
    <AdminShell title="👥 用户与邀请">
      {/* 邀请码 */}
      <div className="card glow-card" style={{ padding: "16px 18px", marginBottom: 14 }}>
        <div style={{ fontWeight: 700, marginBottom: 8 }}>✉️ 管理员邀请码</div>
        <div style={{ fontSize: 13, color: "var(--muted)", marginBottom: 12, lineHeight: 1.8 }}>
          邀请码<b>一次性</b>、7 天有效。把它发给受邀人，对方在前台「注册」页填入邀请码即注册为<b>管理员</b>
          （可登录后台、签发新邀请码）。
        </div>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="备注（邀请谁）"
            style={{ flex: 1, minWidth: 200 }}
          />
          <button className="btn-primary" onClick={createInvite}>
            ＋ 生成邀请码
          </button>
        </div>
        {newCode && (
          <div
            style={{
              marginTop: 12,
              padding: "10px 14px",
              background: "rgba(52,211,153,0.1)",
              border: "1px solid rgba(52,211,153,0.35)",
              borderRadius: 8,
              fontFamily: "monospace",
              fontSize: 14,
              wordBreak: "break-all",
            }}
          >
            ✅ 新邀请码：<b>{newCode}</b>
            <button
              style={{ marginLeft: 10, padding: "3px 10px", fontSize: 12 }}
              onClick={() => navigator.clipboard?.writeText(newCode).then(() => toast("已复制"))}
            >
              复制
            </button>
          </div>
        )}
      </div>

      <div className="table-wrap card" style={{ marginBottom: 22 }}>
        <table className="data">
          <thead>
            <tr>
              <th>邀请码</th>
              <th>备注</th>
              <th>签发人</th>
              <th>签发时间</th>
              <th>有效期至</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {invites.map((i) => {
              const [label, color] = inviteStatus(i);
              return (
                <tr key={i.code}>
                  <td style={{ fontFamily: "monospace", fontSize: 12.5 }}>{i.code}</td>
                  <td>{i.note || "—"}</td>
                  <td>{i.createdBy || "—"}</td>
                  <td style={{ color: "var(--muted)", fontSize: 13 }}>{fmtFullTime(i.createdAt)}</td>
                  <td style={{ color: "var(--muted)", fontSize: 13 }}>{fmtFullTime(i.expiresAt)}</td>
                  <td>
                    <span className="badge" style={{ background: "var(--hover)", color }}>
                      {label}
                    </span>
                  </td>
                  <td>
                    {!i.revoked && !i.usedBy && i.expiresAt > Date.now() && (
                      <button className="btn-danger" style={{ padding: "4px 10px", fontSize: 12.5 }} onClick={() => revoke(i.code)}>
                        撤销
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
            {!invites.length && (
              <tr>
                <td colSpan={7} style={{ textAlign: "center", color: "var(--muted)", padding: 28 }}>
                  暂无邀请码
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* 用户列表 */}
      <div style={{ fontWeight: 700, marginBottom: 10 }}>🗂 注册用户（{users.length}）</div>
      <div className="table-wrap card">
        <table className="data">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>角色</th>
              <th>邀请人</th>
              <th>注册时间</th>
              <th>最近登录</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td>{u.id}</td>
                <td>
                  <b>{u.username}</b>
                </td>
                <td>
                  {u.role === "ADMIN" ? (
                    <span className="badge" style={{ background: "rgba(244,63,94,0.13)", color: "#fb7185" }}>
                      👑 管理员
                    </span>
                  ) : (
                    <span className="badge" style={{ background: "var(--hover)", color: "var(--muted)" }}>
                      用户
                    </span>
                  )}
                </td>
                <td>{u.invitedBy || "—"}</td>
                <td style={{ color: "var(--muted)", fontSize: 13 }}>{fmtFullTime(u.createdAt)}</td>
                <td style={{ color: "var(--muted)", fontSize: 13 }}>{u.lastLoginAt ? fmtFullTime(u.lastLoginAt) : "—"}</td>
                <td>
                  {u.disabled ? (
                    <span className="badge" style={{ background: "rgba(248,113,113,0.13)", color: "var(--danger)" }}>
                      已停用
                    </span>
                  ) : (
                    <span className="badge" style={{ background: "rgba(52,211,153,0.13)", color: "var(--accent)" }}>
                      正常
                    </span>
                  )}
                </td>
                <td>
                  <button style={{ padding: "4px 10px", fontSize: 12.5 }} onClick={() => setDisabled(u, !u.disabled)}>
                    {u.disabled ? "启用" : "停用"}
                  </button>
                </td>
              </tr>
            ))}
            {!users.length && (
              <tr>
                <td colSpan={8} style={{ textAlign: "center", color: "var(--muted)", padding: 28 }}>
                  暂无注册用户
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {toastNode}
    </AdminShell>
  );
}
