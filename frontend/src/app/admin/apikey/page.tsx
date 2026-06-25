"use client";

/** 后台 · API Key 管理（与旧版 admin-apikey.html 等价）。 */

import { useCallback, useEffect, useState } from "react";
import { adminApi, adminPost } from "@/lib/api";
import { fmtFullTime, fmtNum } from "@/lib/format";
import { AdminShell, useToast } from "@/components/AdminShell";

interface Key {
  id: number;
  key: string;
  note?: string;
  active: boolean;
  createdAt: number;
  lastUsedAt: number;
  callCount: number;
  /** 0=后台签发；>0=用户自助生成（归属用户 id）。 */
  userId?: number;
}

export default function AdminApiKeyPage() {
  const [keys, setKeys] = useState<Key[]>([]);
  const [note, setNote] = useState("");
  const [newKey, setNewKey] = useState("");
  const [userNames, setUserNames] = useState<Record<number, string>>({});
  const [toastNode, toast] = useToast();

  const load = useCallback(() => {
    adminApi("/api/boards/apikey/list")
      .then((d) => setKeys(d.keys || []))
      .catch(() => {});
    adminApi("/api/boards/users/list")
      .then((d) => {
        const m: Record<number, string> = {};
        (d.users || []).forEach((u: any) => (m[u.id] = u.username));
        setUserNames(m);
      })
      .catch(() => {});
  }, []);

  useEffect(load, [load]);

  const create = async () => {
    try {
      const r = await adminPost("/api/boards/apikey/create", { note: note.trim() });
      setNewKey(r.key || "");
      setNote("");
      toast("已创建");
      load();
    } catch (e: any) {
      toast(`创建失败：${e.message}`, false);
    }
  };

  const setActive = async (id: number, active: boolean) => {
    try {
      await adminPost("/api/boards/apikey/active", { id, active });
      toast(active ? "已启用" : "已停用");
      load();
    } catch (e: any) {
      toast(`操作失败：${e.message}`, false);
    }
  };

  const del = async (id: number) => {
    if (!confirm(`确认删除 #${id}？该 key 将立即失效。`)) return;
    try {
      await adminPost("/api/boards/apikey/delete", { id });
      toast("已删除");
      load();
    } catch (e: any) {
      toast(`删除失败：${e.message}`, false);
    }
  };

  return (
    <AdminShell title="🔑 API Key 管理">
      <div className="card glow-card" style={{ padding: "16px 18px", marginBottom: 14 }}>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="备注（给谁用的）"
            style={{ flex: 1, minWidth: 200 }}
          />
          <button className="btn-primary" onClick={create}>
            ＋ 创建 Key
          </button>
        </div>
        {newKey && (
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
            ✅ 新 Key（请立即保存）：<b>{newKey}</b>
          </div>
        )}
        <div style={{ marginTop: 12, fontSize: 13, color: "var(--muted)", lineHeight: 1.9 }}>
          用法：<code>GET /api/key/player?name=玩家名&server=区服districtId</code>，请求头{" "}
          <code>X-API-Key: sk_xxx</code>（或 <code>Authorization: Bearer sk_xxx</code>）。
        </div>
      </div>

      <div className="table-wrap card">
        <table className="data">
          <thead>
            <tr>
              <th>ID</th>
              <th>Key</th>
              <th>备注</th>
              <th>归属</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>最近使用</th>
              <th>调用次数</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.id}>
                <td>{k.id}</td>
                <td style={{ fontFamily: "monospace", fontSize: 12.5 }}>{k.key}</td>
                <td>{k.note || "—"}</td>
                <td>
                  {(k.userId ?? 0) > 0 ? (
                    <span className="badge" style={{ background: "rgba(96,165,250,0.13)", color: "var(--primary-strong)" }}>
                      👤 {userNames[k.userId!] || `用户#${k.userId}`}
                    </span>
                  ) : (
                    <span style={{ color: "var(--muted)", fontSize: 12.5 }}>后台签发</span>
                  )}
                </td>
                <td>
                  {k.active ? (
                    <span className="badge" style={{ background: "rgba(52,211,153,0.15)", color: "var(--accent)" }}>
                      启用
                    </span>
                  ) : (
                    <span className="badge" style={{ background: "var(--hover)", color: "var(--muted)" }}>
                      停用
                    </span>
                  )}
                </td>
                <td style={{ color: "var(--muted)", fontSize: 13 }}>{fmtFullTime(k.createdAt)}</td>
                <td style={{ color: "var(--muted)", fontSize: 13 }}>{k.lastUsedAt ? fmtFullTime(k.lastUsedAt) : "—"}</td>
                <td className="num">{fmtNum(k.callCount)}</td>
                <td style={{ display: "flex", gap: 6 }}>
                  <button style={{ padding: "4px 10px", fontSize: 12.5 }} onClick={() => setActive(k.id, !k.active)}>
                    {k.active ? "停用" : "启用"}
                  </button>
                  <button className="btn-danger" style={{ padding: "4px 10px", fontSize: 12.5 }} onClick={() => del(k.id)}>
                    删除
                  </button>
                </td>
              </tr>
            ))}
            {!keys.length && (
              <tr>
                <td colSpan={9} style={{ textAlign: "center", color: "var(--muted)", padding: 32 }}>
                  暂无 API Key
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
