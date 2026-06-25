"use client";

/**
 * 后台「侦察号管理」页（频道大厅用号）。
 * 接口：/api/boards/scout/{list,save,import,delete,enable,probe}。
 *
 * 与抓榜号不同：侦察号的「可登区」不手填——保存/导入后由后端自动全区探测决定（districts + 每区等级）。
 * districts 存的是对外区号 districtId（与频道大厅前端一致），用 SERVER_NAME 映射成区名展示。
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { adminApi, adminPost } from "@/lib/api";
import { AdminShell, useToast } from "@/components/AdminShell";
import { SERVER_NAME } from "@/lib/servers";

interface Scout {
  id: number;
  account: string;
  password: string;
  pauth: string;
  uid: string;
  districts: number[];
  level: number | null;
  probedLevels: Record<string, number>;
  probing: boolean;
  enabled: boolean;
  updatedAt: number | null;
}

interface FormState {
  id: number | null;
  account: string;
  password: string;
  pauth: string;
  uid: string;
}

const EMPTY_FORM: FormState = { id: null, account: "", password: "", pauth: "", uid: "" };

function srvName(districtId: number): string {
  return SERVER_NAME[districtId] || `${districtId}区`;
}

/** pauth 脱敏：>16 位显示前 8 + … + 后 4，点击展开。 */
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

export default function AdminScoutPage() {
  const [toastNode, toast] = useToast();
  const [scouts, setScouts] = useState<Scout[] | null>(null); // null = 加载中
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [bulk, setBulk] = useState("");
  const [importing, setImporting] = useState(false);
  const [probingId, setProbingId] = useState<number | null>(null);

  const loadScouts = useCallback(() => {
    adminApi("/api/boards/scout/list")
      .then((d) => setScouts(d.scouts || []))
      .catch((e: any) => toast(`加载侦察号失败：${e.message}`, false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    loadScouts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 有号正在探测时自动轮询刷新（探测异步，完成后 districts/等级才出现）。
  const anyProbing = !!scouts?.some((s) => s.probing);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  useEffect(() => {
    if (anyProbing && !pollRef.current) {
      pollRef.current = setInterval(loadScouts, 3000);
    } else if (!anyProbing && pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [anyProbing, loadScouts]);

  const startEdit = (s: Scout) => {
    setForm({ id: s.id, account: s.account, password: s.password || "", pauth: s.pauth || "", uid: s.uid || "" });
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const resetForm = () => setForm(EMPTY_FORM);

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
      };
      if (form.id != null) body.id = form.id;
      const d = await adminPost("/api/boards/scout/save", body);
      if (d && d.ok === false) {
        toast(`保存失败：${d.error || ""}`, false);
        return;
      }
      const n = Array.isArray(d?.districts) ? d.districts.length : 0;
      toast(`${form.id != null ? "已保存修改" : "已新建侦察号"}，探测到 ${n} 个可登区`);
      resetForm();
      loadScouts();
    } catch (e: any) {
      toast(`保存失败：${e.message}`, false);
    } finally {
      setSaving(false);
    }
  };

  /** 批量导入：每行一个号，字段用逗号/制表符/空格分隔：account pauth uid [password]。 */
  const doImport = async () => {
    const items = bulk
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const parts = line.split(/[,\t]|\s{1,}/).map((p) => p.trim()).filter(Boolean);
        return { account: parts[0] || "", pauth: parts[1] || "", uid: parts[2] || "", password: parts[3] || "" };
      })
      .filter((it) => it.account);
    if (!items.length) {
      toast("没有可导入的行（格式：账号 pauth uid [密码]，每行一个）", false);
      return;
    }
    setImporting(true);
    try {
      const d = await adminPost("/api/boards/scout/import", { items });
      if (d && d.ok === false) {
        toast(`导入失败：${d.error || ""}`, false);
        return;
      }
      toast(`已导入 ${d.imported ?? items.length} 个号，正在后台并发探测各区…`);
      setBulk("");
      loadScouts();
    } catch (e: any) {
      toast(`导入失败：${e.message}`, false);
    } finally {
      setImporting(false);
    }
  };

  const probe = async (s: Scout) => {
    setProbingId(s.id);
    try {
      const d = await adminPost("/api/boards/scout/probe", { id: s.id });
      if (d && d.ok === false) {
        toast(`探测失败：${d.error || ""}`, false);
        return;
      }
      const n = Array.isArray(d?.districts) ? d.districts.length : 0;
      toast(`探测完成：${n} 个可登区`);
      loadScouts();
    } catch (e: any) {
      toast(`探测失败：${e.message}`, false);
    } finally {
      setProbingId(null);
    }
  };

  const toggleEnabled = async (s: Scout) => {
    try {
      const d = await adminPost("/api/boards/scout/enable", { id: s.id, enabled: !s.enabled });
      if (d && d.ok === false) {
        toast(`操作失败：${d.error || ""}`, false);
        return;
      }
      toast(s.enabled ? "已停用" : "已启用");
      loadScouts();
    } catch (e: any) {
      toast(`操作失败：${e.message}`, false);
    }
  };

  const remove = async (s: Scout) => {
    if (!confirm(`确定删除侦察号「${s.account}」（#${s.id}）？`)) return;
    try {
      const d = await adminPost("/api/boards/scout/delete", { id: s.id });
      if (d && d.ok === false) {
        toast(`删除失败：${d.error || ""}`, false);
        return;
      }
      toast("已删除");
      if (form.id === s.id) resetForm();
      loadScouts();
    } catch (e: any) {
      toast(`删除失败：${e.message}`, false);
    }
  };

  const labelStyle = { fontSize: 13, color: "var(--muted)", display: "block", marginBottom: 4 } as const;

  return (
    <AdminShell title="侦察号管理（频道大厅）">
      {/* ── 新建 / 编辑表单 ── */}
      <div className="card card-pad fade-up" style={{ marginBottom: 18 }}>
        <div style={{ fontWeight: 600, marginBottom: 12 }}>
          {form.id != null ? `编辑侦察号 #${form.id}` : "新增侦察号"}
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: 14 }}>
          <div>
            <label style={labelStyle}>账号名</label>
            <input value={form.account} onChange={(e) => setForm({ ...form, account: e.target.value })} placeholder="登录名" style={{ width: "100%" }} />
          </div>
          <div>
            <label style={labelStyle}>密码（可空）</label>
            <input value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} placeholder="可空" style={{ width: "100%" }} />
          </div>
          <div>
            <label style={labelStyle}>uid（可空）</label>
            <input value={form.uid} onChange={(e) => setForm({ ...form, uid: e.target.value })} placeholder="可空" style={{ width: "100%" }} />
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
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 14, flexWrap: "wrap" }}>
          <button className="btn-primary" onClick={save} disabled={saving} style={{ minWidth: 96 }}>
            {saving ? <span className="spinner" /> : form.id != null ? "保存修改" : "保存"}
          </button>
          <button onClick={resetForm}>清空表单</button>
          <span style={{ color: "var(--muted)", fontSize: 12.5 }}>
            保存后自动用 pauth 全区试登录，<b>可登区与各区等级由探测决定</b>，无需手填。
          </span>
        </div>
      </div>

      {/* ── 批量导入 ── */}
      <div className="card card-pad fade-up" style={{ marginBottom: 18 }}>
        <div style={{ fontWeight: 600, marginBottom: 8 }}>批量导入</div>
        <textarea
          value={bulk}
          onChange={(e) => setBulk(e.target.value)}
          placeholder={"每行一个号，字段用逗号/空格/制表符分隔：\n账号 pauth uid [密码]\n账号2 pauth2 uid2"}
          style={{ width: "100%", minHeight: 90, resize: "vertical", fontFamily: "ui-monospace, Consolas, monospace", fontSize: 13 }}
        />
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 10, flexWrap: "wrap" }}>
          <button className="btn-primary" onClick={doImport} disabled={importing} style={{ minWidth: 96 }}>
            {importing ? <span className="spinner" /> : "导入并探测"}
          </button>
          <span style={{ color: "var(--muted)", fontSize: 12.5 }}>导入后后台并发对每个号全区探测，区与等级稍后自动出现。</span>
        </div>
      </div>

      {/* ── 侦察号列表 ── */}
      <div className="card fade-up">
        <div style={{ padding: "14px 20px 10px", fontWeight: 600, display: "flex", alignItems: "center", gap: 10 }}>
          <span>全部侦察号（{scouts ? scouts.length : "…"}）</span>
          {anyProbing && (
            <span style={{ fontSize: 12.5, color: "var(--muted)", display: "inline-flex", alignItems: "center", gap: 6 }}>
              <span className="spinner" style={{ width: 12, height: 12 }} /> 探测中，自动刷新…
            </span>
          )}
          <button onClick={loadScouts} style={{ marginLeft: "auto", padding: "4px 12px", fontSize: 13 }}>
            刷新
          </button>
        </div>
        {scouts === null ? (
          <div style={{ display: "flex", justifyContent: "center", padding: 36 }}>
            <span className="spinner" />
          </div>
        ) : scouts.length === 0 ? (
          <div style={{ color: "var(--muted)", textAlign: "center", padding: "28px 0 32px", fontSize: 13.5 }}>
            还没有侦察号，用上面的表单新增或批量导入。频道大厅需要至少一个能登的侦察号才会显示数据。
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
                  <th>可登区 · 等级</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {scouts.map((s) => (
                  <tr key={s.id}>
                    <td className="num">{s.id}</td>
                    <td>{s.account}</td>
                    <td>
                      <PauthCell value={s.pauth} />
                    </td>
                    <td>{s.uid || <span style={{ color: "var(--muted)" }}>—</span>}</td>
                    <td style={{ whiteSpace: "normal", maxWidth: 360 }}>
                      {s.probing ? (
                        <span style={{ color: "var(--muted)", display: "inline-flex", alignItems: "center", gap: 6 }}>
                          <span className="spinner" style={{ width: 12, height: 12 }} /> 探测中…
                        </span>
                      ) : (s.districts || []).length ? (
                        (s.districts || []).map((d) => {
                          const lv = s.probedLevels?.[String(d)];
                          return (
                            <span
                              key={d}
                              className="badge"
                              style={{
                                background: "color-mix(in srgb, var(--primary) 14%, transparent)",
                                color: "var(--primary-strong)",
                                margin: "1px 3px 1px 0",
                              }}
                            >
                              {srvName(d)}
                              {lv ? ` Lv${lv}` : ""}
                            </span>
                          );
                        })
                      ) : (
                        <span style={{ color: "var(--muted)" }}>无可登区</span>
                      )}
                    </td>
                    <td>
                      <button
                        onClick={() => toggleEnabled(s)}
                        className="badge"
                        title={s.enabled ? "点击停用" : "点击启用"}
                        style={{
                          border: "none",
                          padding: "3px 12px",
                          background: s.enabled
                            ? "color-mix(in srgb, var(--accent) 16%, transparent)"
                            : "color-mix(in srgb, var(--danger) 14%, transparent)",
                          color: s.enabled ? "var(--accent)" : "var(--danger)",
                        }}
                      >
                        {s.enabled ? "启用中" : "已停用"}
                      </button>
                    </td>
                    <td>
                      <span style={{ display: "inline-flex", gap: 6, flexWrap: "wrap" }}>
                        <button
                          onClick={() => probe(s)}
                          disabled={probingId === s.id || s.probing}
                          style={{ padding: "4px 12px", fontSize: 13 }}
                        >
                          {probingId === s.id ? <span className="spinner" style={{ width: 12, height: 12 }} /> : "探测"}
                        </button>
                        <button onClick={() => startEdit(s)} style={{ padding: "4px 12px", fontSize: 13 }}>
                          编辑
                        </button>
                        <button className="btn-danger" onClick={() => remove(s)} style={{ padding: "4px 12px", fontSize: 13 }}>
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
