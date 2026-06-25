"use client";

/**
 * 后台「抓榜号账号管理」页（移植自旧版 admin-accounts.html 的「抓榜号」Tab）。
 * 接口：/api/boards/districts（区表）、/api/boards/account/{list,save,delete,enable}。
 * 注意：抓榜号 servers 存的是登录号 loginId（非对外区号 districtId）。
 */

import { useCallback, useEffect, useState } from "react";
import { adminApi, adminPost } from "@/lib/api";
import { AdminShell, useToast } from "@/components/AdminShell";

interface District {
  districtId: number;
  loginId: number;
  name: string;
}

interface Acct {
  id: number;
  account: string;
  password: string;
  pauth: string;
  uid: string;
  servers: (number | string)[];
  enabled: boolean;
  available: boolean;
}

/** 表单字段（编辑时带 id，新建时 id 为 null）。 */
interface FormState {
  id: number | null;
  account: string;
  password: string;
  pauth: string;
  uid: string;
  servers: string[]; // 登录号字符串集合
  enabled: boolean;
}

const EMPTY_FORM: FormState = { id: null, account: "", password: "", pauth: "", uid: "", servers: [], enabled: true };

/** pauth 脱敏展示：超过 16 位时显示前 8 位 + … + 后 4 位，点击展开/收起全文。 */
function PauthCell({ value }: { value: string }) {
  const [expanded, setExpanded] = useState(false);
  if (!value) return <span style={{ color: "var(--muted)" }}>—</span>;
  const long = value.length > 16;
  const shown = expanded || !long ? value : `${value.slice(0, 8)}…${value.slice(-4)}`;
  return (
    <code
      className={long ? "longtext" : undefined}
      title={long ? (expanded ? "点击收起" : "点击展开全文") : undefined}
      onClick={() => long && setExpanded((v) => !v)}
      style={{
        fontSize: 12,
        padding: "2px 6px",
        borderRadius: 5,
        background: "var(--hover)",
        wordBreak: "break-all",
        whiteSpace: expanded ? "normal" : "nowrap",
        maxWidth: 360,
        display: "inline-block",
      }}
    >
      {shown}
    </code>
  );
}

export default function AdminAccountsPage() {
  const [toastNode, toast] = useToast();
  const [districts, setDistricts] = useState<District[]>([]);
  const [accounts, setAccounts] = useState<Acct[] | null>(null); // null = 加载中
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);

  /** 登录号 → 区名（列表 badge 展示用）。 */
  const nameOfLoginId = (lid: number | string) => {
    const d = districts.find((x) => String(x.loginId) === String(lid));
    return d ? d.name : `${lid}区`;
  };

  const loadAccounts = useCallback(() => {
    adminApi("/api/boards/account/list")
      .then((d) => setAccounts(d.accounts || []))
      .catch((e: any) => toast(`加载账号失败：${e.message}`, false));
    // toast 引用稳定，无需加入依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    adminApi("/api/boards/districts")
      .then((d) => setDistricts(d.districts || []))
      .catch((e: any) => toast(`加载区表失败：${e.message}`, false));
    loadAccounts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 编辑：把整行填进表单并滚到顶部。 */
  const startEdit = (a: Acct) => {
    setForm({
      id: a.id,
      account: a.account,
      password: a.password || "",
      pauth: a.pauth || "",
      uid: a.uid || "",
      servers: (a.servers || []).map(String),
      enabled: a.enabled,
    });
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const resetForm = () => setForm(EMPTY_FORM);

  /** 区多选：勾选/取消一个登录号。 */
  const toggleServer = (lid: string) =>
    setForm((f) => ({
      ...f,
      servers: f.servers.includes(lid) ? f.servers.filter((s) => s !== lid) : [...f.servers, lid],
    }));

  const save = async () => {
    const account = form.account.trim();
    if (!account) {
      toast("账号名不能为空", false);
      return;
    }
    setSaving(true);
    try {
      const body: Record<string, unknown> = {
        account,
        password: form.password.trim(),
        pauth: form.pauth.trim(),
        uid: form.uid.trim(),
        servers: form.servers,
        enabled: form.enabled,
      };
      if (form.id != null) body.id = form.id;
      const d = await adminPost("/api/boards/account/save", body);
      if (d && d.ok === false) {
        toast(`保存失败：${d.error || ""}`, false);
        return;
      }
      toast(form.id != null ? "已保存修改" : "已新建账号");
      resetForm();
      loadAccounts();
    } catch (e: any) {
      toast(`保存失败：${e.message}`, false);
    } finally {
      setSaving(false);
    }
  };

  /** 启用/停用开关。 */
  const toggleEnabled = async (a: Acct) => {
    try {
      const d = await adminPost("/api/boards/account/enable", { id: a.id, enabled: !a.enabled });
      if (d && d.ok === false) {
        toast(`操作失败：${d.error || ""}`, false);
        return;
      }
      toast(a.enabled ? "已停用" : "已启用");
      loadAccounts();
    } catch (e: any) {
      toast(`操作失败：${e.message}`, false);
    }
  };

  const remove = async (a: Acct) => {
    if (!confirm(`确定删除抓榜号「${a.account}」（#${a.id}）？`)) return;
    try {
      const d = await adminPost("/api/boards/account/delete", { id: a.id });
      if (d && d.ok === false) {
        toast(`删除失败：${d.error || ""}`, false);
        return;
      }
      toast("已删除");
      if (form.id === a.id) resetForm();
      loadAccounts();
    } catch (e: any) {
      toast(`删除失败：${e.message}`, false);
    }
  };

  const labelStyle = { fontSize: 13, color: "var(--muted)", display: "block", marginBottom: 4 } as const;

  return (
    <AdminShell title="抓榜号账号管理">
      {/* ── 新建 / 编辑表单 ── */}
      <div className="card card-pad fade-up" style={{ marginBottom: 18 }}>
        <div style={{ fontWeight: 600, marginBottom: 12 }}>
          {form.id != null ? `编辑抓榜号 #${form.id}` : "新增抓榜号"}
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: 14 }}>
          <div>
            <label style={labelStyle}>账号名</label>
            <input
              value={form.account}
              onChange={(e) => setForm({ ...form, account: e.target.value })}
              placeholder="登录名"
              style={{ width: "100%" }}
            />
          </div>
          <div>
            <label style={labelStyle}>密码</label>
            <input
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              placeholder="密码"
              style={{ width: "100%" }}
            />
          </div>
          <div>
            <label style={labelStyle}>uid（可空）</label>
            <input
              value={form.uid}
              onChange={(e) => setForm({ ...form, uid: e.target.value })}
              placeholder="可空"
              style={{ width: "100%" }}
            />
          </div>
        </div>
        <div style={{ marginTop: 14 }}>
          <label style={labelStyle}>pauth（登录令牌）</label>
          <textarea
            value={form.pauth}
            onChange={(e) => setForm({ ...form, pauth: e.target.value })}
            placeholder="登录令牌"
            style={{ width: "100%", minHeight: 62, resize: "vertical", fontFamily: "ui-monospace, Consolas, monospace", fontSize: 13 }}
          />
        </div>
        <div style={{ marginTop: 14 }}>
          <label style={labelStyle}>可登录的区（多选，存登录号）</label>
          <div
            style={{
              display: "flex",
              flexWrap: "wrap",
              gap: "6px 14px",
              padding: "10px 12px",
              border: "1px solid var(--border)",
              borderRadius: 10,
            }}
          >
            {districts.length === 0 && <span style={{ color: "var(--muted)", fontSize: 13 }}>区表加载中…</span>}
            {districts.map((d) => (
              <label
                key={d.loginId}
                style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 13.5, cursor: "pointer", margin: 0 }}
              >
                <input
                  type="checkbox"
                  checked={form.servers.includes(String(d.loginId))}
                  onChange={() => toggleServer(String(d.loginId))}
                  style={{ width: "auto", margin: 0 }}
                />
                {d.name}
              </label>
            ))}
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 14, flexWrap: "wrap" }}>
          <button className="btn-primary" onClick={save} disabled={saving} style={{ minWidth: 96 }}>
            {saving ? <span className="spinner" /> : form.id != null ? "保存修改" : "保存"}
          </button>
          <button onClick={resetForm}>清空表单</button>
          <label style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 13.5, cursor: "pointer", margin: 0 }}>
            <input
              type="checkbox"
              checked={form.enabled}
              onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
              style={{ width: "auto", margin: 0 }}
            />
            启用
          </label>
          <span style={{ color: "var(--muted)", fontSize: 12.5 }}>提示：大批量号建议用 PG dump 导入，这里用于日常增删改个别号。</span>
        </div>
      </div>

      {/* ── 账号列表 ── */}
      <div className="card fade-up">
        <div style={{ padding: "14px 20px 10px", fontWeight: 600 }}>
          全部抓榜号（{accounts ? accounts.length : "…"}）
        </div>
        {accounts === null ? (
          <div style={{ display: "flex", justifyContent: "center", padding: 36 }}>
            <span className="spinner" />
          </div>
        ) : accounts.length === 0 ? (
          <div style={{ color: "var(--muted)", textAlign: "center", padding: "28px 0 32px", fontSize: 13.5 }}>
            还没有抓榜号，用上面的表单新增。
          </div>
        ) : (
          <div className="table-wrap" style={{ border: "none", borderTop: "1px solid var(--border)", borderRadius: 0 }}>
            <table className="data">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>账号</th>
                  <th>pauth</th>
                  <th>uid</th>
                  <th>可用区服</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {accounts.map((a) => (
                  <tr key={a.id}>
                    <td className="num">{a.id}</td>
                    <td>{a.account}</td>
                    <td>
                      <PauthCell value={a.pauth} />
                    </td>
                    <td>{a.uid || <span style={{ color: "var(--muted)" }}>—</span>}</td>
                    <td style={{ whiteSpace: "normal", maxWidth: 320 }}>
                      {(a.servers || []).length ? (
                        (a.servers || []).map((s) => (
                          <span
                            key={String(s)}
                            className="badge"
                            style={{
                              background: "color-mix(in srgb, var(--primary) 14%, transparent)",
                              color: "var(--primary-strong)",
                              margin: "1px 3px 1px 0",
                            }}
                          >
                            {nameOfLoginId(s)}
                          </span>
                        ))
                      ) : (
                        <span style={{ color: "var(--muted)" }}>—</span>
                      )}
                    </td>
                    <td>
                      {/* enabled 开关：点击切换启停 */}
                      <button
                        onClick={() => toggleEnabled(a)}
                        className="badge"
                        title={a.enabled ? "点击停用" : "点击启用"}
                        style={{
                          border: "none",
                          padding: "3px 12px",
                          background: a.enabled
                            ? "color-mix(in srgb, var(--accent) 16%, transparent)"
                            : "color-mix(in srgb, var(--danger) 14%, transparent)",
                          color: a.enabled ? "var(--accent)" : "var(--danger)",
                        }}
                      >
                        {a.enabled ? "启用中" : "已停用"}
                      </button>
                    </td>
                    <td>
                      <span style={{ display: "inline-flex", gap: 6 }}>
                        <button onClick={() => startEdit(a)} style={{ padding: "4px 12px", fontSize: 13 }}>
                          编辑
                        </button>
                        <button className="btn-danger" onClick={() => remove(a)} style={{ padding: "4px 12px", fontSize: 13 }}>
                          删除
                        </button>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {toastNode}
    </AdminShell>
  );
}
