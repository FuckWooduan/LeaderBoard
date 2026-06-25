"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, post, ApiError } from "@/lib/api";
import { fmtNum, fmtTime } from "@/lib/format";
import { SERVERS, SERVER_NAME } from "@/lib/servers";
import { useAuth } from "@/lib/auth";
import { DistrictTag, RealmTag } from "@/components/cells";
import { Pager } from "@/components/Pager";

const PAGE_SIZE = 100;

interface PlayerResult {
  available: boolean;
  notFound?: boolean;
  queryFailed?: boolean;
  message?: string;
  charName?: string;
  online?: boolean;
  lv?: number;
  teamName?: string;
  serverName?: string;
  logoutTime?: number;
  characterId?: number;
  power?: { rank: number; value: number };
  exp?: { rank: number; value: number };
}

function PowerExpCards({ d }: { d: PlayerResult }) {
  if (!d.power && !d.exp) return null;
  const card = (title: string, icon: string, rank: number, label: string, value: number, grad: string) => (
    <div
      className="card"
      style={{ flex: "1 1 200px", padding: "14px 18px", backgroundImage: grad, minWidth: 180 }}
    >
      <div style={{ fontSize: 13, color: "var(--muted)" }}>
        {icon} {title}
      </div>
      <div style={{ fontSize: 22, fontWeight: 800, margin: "4px 0" }}>第 {fmtNum(rank)} 名</div>
      <div style={{ fontSize: 13, color: "var(--muted)" }}>
        {label} {fmtNum(value)}
      </div>
    </div>
  );
  return (
    <div style={{ display: "flex", gap: 12, flexWrap: "wrap", marginTop: 14 }}>
      {d.power &&
        card("跨服战力榜", "⚔️", d.power.rank, "战力", d.power.value, "linear-gradient(140deg, rgba(79,141,249,0.14), transparent)")}
      {d.exp &&
        card("跨服经验榜", "📖", d.exp.rank, "经验", d.exp.value, "linear-gradient(140deg, rgba(52,211,153,0.13), transparent)")}
    </div>
  );
}

function SubscribeForm({ name, server }: { name: string; server: string }) {
  const [email, setEmail] = useState("");
  const [opts, setOpts] = useState({ rename: true, vip: true, realm: true });
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!email.includes("@")) {
      setMsg({ text: "请填写有效邮箱", ok: false });
      return;
    }
    if (!opts.rename && !opts.vip && !opts.realm) {
      setMsg({ text: "请至少勾选一项", ok: false });
      return;
    }
    setBusy(true);
    setMsg({ text: "提交中…", ok: true });
    try {
      const r = await post("/api/public/player/subscribe", { name, server, email: email.trim(), ...opts });
      setMsg({ text: r.message || (r.ok ? "订阅成功，已发确认邮件" : "订阅失败"), ok: r.ok });
    } catch (e: any) {
      setMsg({ text: `订阅失败：${e.message}`, ok: false });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card" style={{ marginTop: 16, padding: "14px 16px" }}>
      <div style={{ fontWeight: 600, marginBottom: 6 }}>🔔 订阅该玩家变化通知</div>
      <div style={{ fontSize: 12, color: "var(--muted)", marginBottom: 10 }}>
        检测源为「同服战力榜」，仅能订阅在该榜上的玩家；发生变化时邮件通知你。
      </div>
      <div style={{ marginBottom: 10, display: "flex", gap: 16, flexWrap: "wrap" }}>
        {(
          [
            ["rename", "改名"],
            ["vip", "VIP提升"],
            ["realm", "境界提升"],
          ] as const
        ).map(([k, label]) => (
          <label key={k} style={{ fontSize: 14, display: "inline-flex", gap: 6, alignItems: "center" }}>
            <input
              type="checkbox"
              checked={opts[k]}
              onChange={(e) => setOpts((o) => ({ ...o, [k]: e.target.checked }))}
            />
            {label}
          </label>
        ))}
      </div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="你的邮箱"
          style={{ flex: 1, minWidth: 180 }}
        />
        <button onClick={submit} disabled={busy}>
          订阅
        </button>
      </div>
      {msg && (
        <div style={{ marginTop: 8, fontSize: 13, color: msg.ok ? "var(--accent)" : "var(--danger)" }}>{msg.text}</div>
      )}
    </div>
  );
}

/** 查无此人时的「你可能想找？」跨服战力榜模糊建议。 */
function Suggest({ name }: { name: string }) {
  const [page, setPage] = useState(1);
  const [d, setD] = useState<any>(null);
  const [state, setState] = useState<"loading" | "done" | "fail">("loading");

  useEffect(() => {
    setState("loading");
    api(`/api/public/tab?tab=power&q=${encodeURIComponent(name)}&page=${page}`)
      .then((x) => {
        setD(x);
        setState("done");
      })
      .catch(() => setState("fail"));
  }, [name, page]);

  if (state === "loading")
    return (
      <div className="card card-pad" style={{ marginTop: 14, color: "var(--muted)" }}>
        正在跨服战力榜里帮你模糊找「{name}」…
      </div>
    );
  if (state === "fail" || !d?.rows?.length)
    return (
      <div className="card card-pad" style={{ marginTop: 14, color: "var(--muted)" }}>
        跨服战力榜里也没有名字或战队含「{name}」的玩家
      </div>
    );
  const totalPages = Math.max(1, Math.ceil((d.total || 0) / PAGE_SIZE));
  return (
    <div className="card" style={{ marginTop: 14, padding: "16px 18px" }}>
      <div style={{ fontWeight: 600, marginBottom: 10 }}>
        你可能想找？{" "}
        <span style={{ color: "var(--muted)", fontWeight: 400, fontSize: 13 }}>
          跨服战力榜中名字/战队含「{name}」，共 {fmtNum(d.total)} 个
        </span>
      </div>
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>名次</th>
              <th>角色名</th>
              <th>战队</th>
              <th>区服</th>
              <th>战力</th>
              <th>境界</th>
            </tr>
          </thead>
          <tbody>
            {d.rows.map((r: any, i: number) => (
              <tr key={i}>
                <td>{r.rank}</td>
                <td>{r.info_char_name || ""}</td>
                <td>{r.info_team_name || "—"}</td>
                <td>
                  <DistrictTag v={r.info_login_id} />
                </td>
                <td className="num">{fmtNum(r.score_number)}</td>
                <td>
                  <RealmTag v={r.score_rebirth} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pager page={page} totalPages={totalPages} total={d.total || 0} unit="个" onPage={setPage} />
    </div>
  );
}

export default function PlayerPage() {
  const { user, ready } = useAuth();
  const router = useRouter();
  const params = useSearchParams();

  const [name, setName] = useState(params.get("name") || params.get("q") || "");
  const [server, setServer] = useState(params.get("server") || String(SERVERS[0][0]));
  const [result, setResult] = useState<PlayerResult | null>(null);
  const [status, setStatus] = useState<string>("");
  const [loc, setLoc] = useState<string | null>(null);
  const [suggestFor, setSuggestFor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // 防竞态：每次查询自增序号，异步的频道补查回来后只接受「最新一次查询」的结果
  const querySeq = useRef(0);

  const query = useCallback(
    async (qName: string, qServer: string) => {
      if (!qName.trim()) {
        setStatus("请输入玩家名");
        return;
      }
      const seq = ++querySeq.current;
      setBusy(true);
      setResult(null);
      setSuggestFor(null);
      setLoc(null);
      setStatus("查询中…");
      try {
        const d: PlayerResult = await api(
          `/api/public/player?name=${encodeURIComponent(qName.trim())}&server=${encodeURIComponent(qServer)}`
        );
        if (seq !== querySeq.current) return; // 已有更新的查询，丢弃本次结果
        setStatus("");
        setResult(d);
        if (d.notFound) setSuggestFor(qName.trim());
        if (d.available !== false && !d.notFound && !d.queryFailed && d.online && d.characterId != null) {
          setLoc("⏳ 正在查询频道…");
          api(`/api/public/player/location?server=${encodeURIComponent(qServer)}&charId=${d.characterId}`)
            .then((L) => {
              if (seq !== querySeq.current) return; // 旧请求的频道结果不覆盖新查询
              setLoc(
                L?.available
                  ? `频道 ${L.channel || "—"} · 房间 ${L.room || "—"} · ${L.inGame ? "游戏中" : "在大厅"}`
                  : "频道信息查询超时"
              );
            })
            .catch(() => {
              if (seq === querySeq.current) setLoc("频道信息查询失败");
            });
        }
      } catch (e: any) {
        if (seq !== querySeq.current) return;
        if (e instanceof ApiError && e.status === 401) {
          setStatus("玩家在线查询需要登录账号（防滥用），请先登录。");
        } else {
          setStatus(`查询失败：${e.message}`);
        }
      } finally {
        setBusy(false);
      }
    },
    []
  );

  // URL 带参（深链/旧 hash 跳转）自动查询
  useEffect(() => {
    const n = params.get("name") || params.get("q");
    if (n && ready && user) {
      setName(n);
      void query(n, params.get("server") || String(SERVERS[0][0]));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, user]);

  const trigger = () => {
    const qs = new URLSearchParams({ server, name: name.trim() });
    router.replace(`/player?${qs}`, { scroll: false });
    void query(name, server);
  };

  if (ready && !user) {
    return (
      <div className="card glow-card fade-up" style={{ maxWidth: 560, margin: "40px auto", padding: "36px 32px", textAlign: "center" }}>
        <div style={{ fontSize: 44, marginBottom: 10 }}>🔐</div>
        <h2 style={{ margin: "0 0 8px" }}>玩家在线查询需要登录</h2>
        <p style={{ color: "var(--muted)", fontSize: 14, lineHeight: 1.8 }}>
          该查询会实时连接游戏服务器、成本较高，为防滥用需登录账号后使用。
          <br />
          注册免费，只需要一个用户名和密码。
        </p>
        <div style={{ display: "flex", gap: 10, justifyContent: "center", marginTop: 18 }}>
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

  return (
    <div className="fade-up">
      <div className="card glow-card" style={{ padding: "18px 20px", marginBottom: 14 }}>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "flex-end" }}>
          <div style={{ flex: "1 1 220px" }}>
            <label style={{ fontSize: 13, color: "var(--muted)", display: "block", marginBottom: 4 }}>玩家名</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && trigger()}
              placeholder="角色名"
              style={{ width: "100%" }}
            />
          </div>
          <div>
            <label style={{ fontSize: 13, color: "var(--muted)", display: "block", marginBottom: 4 }}>区服</label>
            <select value={server} onChange={(e) => setServer(e.target.value)}>
              {SERVERS.map(([id, n]) => (
                <option key={id} value={id}>
                  {n}
                </option>
              ))}
            </select>
          </div>
          <button className="btn-primary" onClick={trigger} disabled={busy}>
            {busy ? <span className="spinner" /> : "🔍 查询"}
          </button>
        </div>
      </div>

      <div
        className="card"
        style={{
          padding: "8px 12px",
          marginBottom: 14,
          fontSize: 13,
          color: "var(--warn)",
          background: "rgba(251,191,36,0.07)",
          borderColor: "rgba(251,191,36,0.3)",
        }}
      >
        🔔 查到玩家后，可在结果卡片下方<b>订阅 TA 的「改名 / VIP提升 / 境界提升」通知</b>，发生变化邮件提醒你。
      </div>

      <div className="card" style={{ padding: "20px 22px" }}>
        {status && (
          <div style={{ color: "var(--muted)" }}>
            {status}
            {status.includes("登录") && (
              <span style={{ marginLeft: 10 }}>
                <Link href="/login">去登录 →</Link>
              </span>
            )}
          </div>
        )}
        {!status && !result && (
          <div style={{ color: "var(--muted)" }}>选择区服 + 输入玩家名后查询在线情况；查到后可在下方订阅该玩家变化通知</div>
        )}
        {result && result.available === false && (
          <div style={{ color: "var(--muted)" }}>{result.message || "暂不可用"}</div>
        )}
        {result && result.available !== false && result.notFound && (
          <div style={{ color: "var(--muted)" }}>
            没找到「{name}」这名玩家（{SERVER_NAME[Number(server)] || server}）。下面是模糊匹配的结果：
          </div>
        )}
        {result && result.available !== false && !result.notFound && result.queryFailed && (
          <>
            <div style={{ fontSize: 18 }}>
              <b>{result.charName || name}</b>
            </div>
            <div style={{ color: "var(--warn)", marginTop: 6 }}>⚠ {result.message || "在线状态查询超时"}</div>
            <PowerExpCards d={result} />
            <SubscribeForm name={result.charName || name} server={server} />
          </>
        )}
        {result && result.available !== false && !result.notFound && !result.queryFailed && (
          <>
            <div style={{ fontSize: 18, display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
              <b>{result.charName || name}</b>
              {result.online ? (
                <span className="badge" style={{ background: "rgba(52,211,153,0.16)", color: "var(--accent)" }}>
                  ● 在线
                </span>
              ) : (
                <span className="badge" style={{ background: "var(--hover)", color: "var(--muted)" }}>
                  ○ 离线
                </span>
              )}
            </div>
            <div style={{ color: "var(--muted)", marginTop: 8 }}>
              {result.serverName || SERVER_NAME[Number(server)] || ""} · 等级 {result.lv ?? "-"} · 战队{" "}
              {result.teamName || "—"}
            </div>
            <PowerExpCards d={result} />
            {result.online && loc && <div style={{ color: "var(--muted)", marginTop: 10 }}>{loc}</div>}
            {!result.online && result.logoutTime ? (
              <div style={{ color: "var(--muted)", marginTop: 10 }}>上次下线 {fmtTime(result.logoutTime)}</div>
            ) : null}
            <SubscribeForm name={result.charName || name} server={server} />
          </>
        )}
      </div>

      {suggestFor && <Suggest name={suggestFor} />}
    </div>
  );
}
