"use client";

/**
 * 后台「代理节点管理」页（sing-box trojan / anytls 出站节点）。
 * 接口：/api/boards/proxy-node/{list,save,import,delete,enable} + /api/boards/proxy-test。
 *
 * 节点保存/删除/启停后端会自动重生成 sing-box 配置并热重载(pkill 看门狗自恢复)。
 * 侦察号长连接与玩家查询都经这些节点出站。
 */

import { useCallback, useEffect, useState } from "react";
import { adminApi, adminPost } from "@/lib/api";
import { AdminShell, useToast } from "@/components/AdminShell";

interface Node {
  id: number;
  type: string; // "trojan" | "anytls"
  name: string;
  server: string;
  port: number;
  password: string;
  security: string; // "none" | "tls"
  sni: string;
  insecure: boolean;
  enabled: boolean;
  updatedAt: number | null;
}

interface FormState {
  id: number | null;
  type: string;
  name: string;
  server: string;
  port: string;
  password: string;
  security: string;
  sni: string;
  insecure: boolean;
}

const EMPTY_FORM: FormState = {
  id: null,
  type: "trojan",
  name: "",
  server: "",
  port: "",
  password: "",
  security: "none",
  sni: "",
  insecure: false,
};

/** 密码脱敏：>12 位显示前 4 + … + 后 4，点击展开。 */
function SecretCell({ value }: { value: string }) {
  const [expanded, setExpanded] = useState(false);
  if (!value) return <span style={{ color: "var(--muted)" }}>—</span>;
  const long = value.length > 12;
  const shown = expanded || !long ? value : `${value.slice(0, 4)}…${value.slice(-4)}`;
  return (
    <code
      title={long ? (expanded ? "点击收起" : "点击展开") : undefined}
      onClick={() => long && setExpanded((v) => !v)}
      style={{
        fontSize: 12,
        padding: "2px 6px",
        borderRadius: 5,
        background: "var(--hover)",
        wordBreak: "break-all",
        cursor: long ? "pointer" : "default",
      }}
    >
      {shown}
    </code>
  );
}

export default function AdminProxyPage() {
  const [toastNode, toast] = useToast();
  const [nodes, setNodes] = useState<Node[] | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [bulk, setBulk] = useState("");
  const [importing, setImporting] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<string>("");

  const load = useCallback(() => {
    adminApi("/api/boards/proxy-node/list")
      .then((d) => setNodes(d.nodes || []))
      .catch((e: any) => toast(`加载节点失败：${e.message}`, false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startEdit = (n: Node) => {
    setForm({
      id: n.id,
      type: n.type || "trojan",
      name: n.name || "",
      server: n.server,
      port: String(n.port),
      password: n.password || "",
      security: n.security || "none",
      sni: n.sni || "",
      insecure: !!n.insecure,
    });
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const resetForm = () => setForm(EMPTY_FORM);

  const save = async () => {
    const server = form.server.trim();
    const port = parseInt(form.port.trim(), 10);
    if (!server || !port) {
      toast("server / port 不能为空", false);
      return;
    }
    setSaving(true);
    try {
      const isAnytls = form.type === "anytls";
      const body: Record<string, unknown> = {
        type: form.type,
        name: form.name.trim(),
        server,
        port,
        password: form.password.trim(),
        security: isAnytls ? "tls" : form.security, // anytls 恒 TLS
        sni: form.sni.trim(),
        insecure: form.insecure,
        enabled: true,
      };
      if (form.id != null) body.id = form.id;
      const d = await adminPost("/api/boards/proxy-node/save", body);
      if (d && d.ok === false) {
        toast(`保存失败：${d.error || ""}`, false);
        return;
      }
      toast(form.id != null ? "已保存并重载 sing-box" : "已新增并重载 sing-box");
      resetForm();
      load();
    } catch (e: any) {
      toast(`保存失败：${e.message}`, false);
    } finally {
      setSaving(false);
    }
  };

  const doImport = async () => {
    const links = bulk.trim();
    if (!links) {
      toast("粘贴至少一条 trojan:// 或 anytls:// 链接", false);
      return;
    }
    setImporting(true);
    try {
      const d = await adminPost("/api/boards/proxy-node/import", { links });
      if (d && d.ok === false) {
        toast(`导入失败：${(d.errors || []).join("；") || d.error || ""}`, false);
        return;
      }
      const errs = Array.isArray(d.errors) && d.errors.length ? `（${d.errors.length} 条失败）` : "";
      toast(`已导入 ${d.imported ?? 0} 个节点并重载${errs}`);
      setBulk("");
      load();
    } catch (e: any) {
      toast(`导入失败：${e.message}`, false);
    } finally {
      setImporting(false);
    }
  };

  const toggleEnabled = async (n: Node) => {
    try {
      const d = await adminPost("/api/boards/proxy-node/enable", { id: n.id, enabled: !n.enabled });
      if (d && d.ok === false) {
        toast(`操作失败：${d.error || ""}`, false);
        return;
      }
      toast(n.enabled ? "已停用并重载" : "已启用并重载");
      load();
    } catch (e: any) {
      toast(`操作失败：${e.message}`, false);
    }
  };

  const remove = async (n: Node) => {
    if (!confirm(`确定删除节点「${n.name || n.server}」（#${n.id}）？`)) return;
    try {
      const d = await adminPost("/api/boards/proxy-node/delete", { id: n.id });
      if (d && d.ok === false) {
        toast(`删除失败：${d.error || ""}`, false);
        return;
      }
      toast("已删除并重载");
      if (form.id === n.id) resetForm();
      load();
    } catch (e: any) {
      toast(`删除失败：${e.message}`, false);
    }
  };

  /** 实测当前 sing-box 各 socks 出口端口（用已验证账号登录游戏服，统计 ok/rc4/fail）。 */
  const runTest = async () => {
    setTesting(true);
    setTestResult("");
    try {
      const d = await adminPost("/api/boards/proxy-test", {});
      if (d && d.ok === false) {
        setTestResult(`无法实测：${d.message || ""}`);
        return;
      }
      const good = d.goodPorts ?? 0;
      const total = d.totalPorts ?? 0;
      const detail = (d.ports || [])
        .map((p: any) => `:${p.port} ok=${p.ok} rc4=${p.rc4} fail=${p.fail}`)
        .join("\n");
      setTestResult(`可用出口端口 ${good}/${total}\n${detail}`);
      toast(`实测完成：${good}/${total} 个端口可用`);
    } catch (e: any) {
      setTestResult(`实测失败：${e.message}`);
      toast(`实测失败：${e.message}`, false);
    } finally {
      setTesting(false);
    }
  };

  const labelStyle = { fontSize: 13, color: "var(--muted)", display: "block", marginBottom: 4 } as const;

  return (
    <AdminShell title="代理节点管理（sing-box / trojan · anytls）">
      {/* ── 新建 / 编辑 ── */}
      <div className="card card-pad fade-up" style={{ marginBottom: 18 }}>
        <div style={{ fontWeight: 600, marginBottom: 12 }}>
          {form.id != null ? `编辑节点 #${form.id}` : "新增节点"}
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))", gap: 14 }}>
          <div>
            <label style={labelStyle}>类型</label>
            <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} style={{ width: "100%" }}>
              <option value="trojan">trojan</option>
              <option value="anytls">anytls（恒 TLS）</option>
            </select>
          </div>
          <div>
            <label style={labelStyle}>名称（备注）</label>
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="节点备注" style={{ width: "100%" }} />
          </div>
          <div>
            <label style={labelStyle}>服务器地址</label>
            <input value={form.server} onChange={(e) => setForm({ ...form, server: e.target.value })} placeholder="103.x.x.x" style={{ width: "100%" }} />
          </div>
          <div>
            <label style={labelStyle}>端口</label>
            <input value={form.port} onChange={(e) => setForm({ ...form, port: e.target.value })} placeholder="59900" style={{ width: "100%" }} />
          </div>
          <div>
            <label style={labelStyle}>密码</label>
            <input value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} placeholder={form.type === "anytls" ? "anytls 密码（UUID）" : "trojan 密码"} style={{ width: "100%" }} />
          </div>
          {form.type !== "anytls" && (
            <div>
              <label style={labelStyle}>安全（TLS）</label>
              <select value={form.security} onChange={(e) => setForm({ ...form, security: e.target.value })} style={{ width: "100%" }}>
                <option value="none">none（裸 TCP，无 TLS）</option>
                <option value="tls">tls</option>
              </select>
            </div>
          )}
          {(form.type === "anytls" || form.security === "tls") && (
            <>
              <div>
                <label style={labelStyle}>SNI（可空）</label>
                <input value={form.sni} onChange={(e) => setForm({ ...form, sni: e.target.value })} placeholder="证书域名" style={{ width: "100%" }} />
              </div>
              <div>
                <label style={labelStyle}>跳过证书校验</label>
                <label style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 13 }}>
                  <input type="checkbox" checked={form.insecure} onChange={(e) => setForm({ ...form, insecure: e.target.checked })} />
                  allowInsecure
                </label>
              </div>
            </>
          )}
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 14, flexWrap: "wrap" }}>
          <button className="btn-primary" onClick={save} disabled={saving} style={{ minWidth: 96 }}>
            {saving ? <span className="spinner" /> : form.id != null ? "保存修改" : "保存"}
          </button>
          <button onClick={resetForm}>清空表单</button>
          <span style={{ color: "var(--muted)", fontSize: 12.5 }}>保存后自动重生成 sing-box 配置并热重载。</span>
        </div>
      </div>

      {/* ── 批量导入 trojan:// / anytls:// ── */}
      <div className="card card-pad fade-up" style={{ marginBottom: 18 }}>
        <div style={{ fontWeight: 600, marginBottom: 8 }}>粘贴 trojan:// / anytls:// 链接导入</div>
        <textarea
          value={bulk}
          onChange={(e) => setBulk(e.target.value)}
          placeholder={"每行一条，自动识别协议：\ntrojan://password@host:port?security=none#名称\nanytls://uuid@host:port?sni=baidu.com&insecure=1#名称"}
          style={{ width: "100%", minHeight: 90, resize: "vertical", fontFamily: "ui-monospace, Consolas, monospace", fontSize: 13 }}
        />
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 10, flexWrap: "wrap" }}>
          <button className="btn-primary" onClick={doImport} disabled={importing} style={{ minWidth: 96 }}>
            {importing ? <span className="spinner" /> : "导入并重载"}
          </button>
          <span style={{ color: "var(--muted)", fontSize: 12.5 }}>按行前缀自动识别 trojan / anytls；导入即启用。</span>
        </div>
      </div>

      {/* ── 节点列表 ── */}
      <div className="card fade-up">
        <div style={{ padding: "14px 20px 10px", fontWeight: 600, display: "flex", alignItems: "center", gap: 10 }}>
          <span>全部节点（{nodes ? nodes.length : "…"}）</span>
          <button onClick={runTest} disabled={testing} style={{ marginLeft: "auto", padding: "4px 12px", fontSize: 13 }}>
            {testing ? <span className="spinner" style={{ width: 12, height: 12 }} /> : "实测出口端口"}
          </button>
          <button onClick={load} style={{ padding: "4px 12px", fontSize: 13 }}>刷新</button>
        </div>
        {testResult && (
          <pre style={{ margin: "0 20px 12px", padding: 12, background: "var(--hover)", borderRadius: 8, fontSize: 12.5, whiteSpace: "pre-wrap" }}>
            {testResult}
          </pre>
        )}
        {nodes === null ? (
          <div style={{ display: "flex", justifyContent: "center", padding: 36 }}>
            <span className="spinner" />
          </div>
        ) : nodes.length === 0 ? (
          <div style={{ color: "var(--muted)", textAlign: "center", padding: "28px 0 32px", fontSize: 13.5 }}>
            还没有代理节点。新增或粘贴 trojan:// / anytls:// 链接导入。无节点时 sing-box 全部直连（出口=服务器本机 IP）。
          </div>
        ) : (
          <div className="table-wrap" style={{ border: "none", borderTop: "1px solid var(--border)", borderRadius: 0 }}>
            <table className="data">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>类型</th>
                  <th>名称</th>
                  <th>服务器</th>
                  <th>端口</th>
                  <th>密码</th>
                  <th>TLS</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {nodes.map((n) => (
                  <tr key={n.id}>
                    <td className="num">{n.id}</td>
                    <td><code style={{ fontSize: 12 }}>{n.type || "trojan"}</code></td>
                    <td>{n.name || <span style={{ color: "var(--muted)" }}>—</span>}</td>
                    <td><code style={{ fontSize: 12.5 }}>{n.server}</code></td>
                    <td className="num">{n.port}</td>
                    <td><SecretCell value={n.password} /></td>
                    <td>{n.security === "tls" ? `tls${n.insecure ? "(insecure)" : ""}` : "none"}</td>
                    <td>
                      <button
                        onClick={() => toggleEnabled(n)}
                        className="badge"
                        title={n.enabled ? "点击停用" : "点击启用"}
                        style={{
                          border: "none",
                          padding: "3px 12px",
                          background: n.enabled
                            ? "color-mix(in srgb, var(--accent) 16%, transparent)"
                            : "color-mix(in srgb, var(--danger) 14%, transparent)",
                          color: n.enabled ? "var(--accent)" : "var(--danger)",
                        }}
                      >
                        {n.enabled ? "启用中" : "已停用"}
                      </button>
                    </td>
                    <td>
                      <span style={{ display: "inline-flex", gap: 6, flexWrap: "wrap" }}>
                        <button onClick={() => startEdit(n)} style={{ padding: "4px 12px", fontSize: 13 }}>编辑</button>
                        <button className="btn-danger" onClick={() => remove(n)} style={{ padding: "4px 12px", fontSize: 13 }}>删除</button>
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
