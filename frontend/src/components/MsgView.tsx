"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, post } from "@/lib/api";
import { fmtNum, fmtTime } from "@/lib/format";
import { Markdown } from "@/components/Markdown";
import { useAuth } from "@/lib/auth";

const PAGE_SIZE = 20;
const MAX_CONTENT = 2000;

interface Msg {
  id: number;
  nickname?: string;
  content: string;
  createdAt: number;
  pinned?: boolean;
  reply?: string;
  replyAt?: number;
  /** 该条吸收的相似刷屏留言数（每小时 AI 整理；>0 显示「已合并」标记）。 */
  mergedCount?: number;
}

export interface MsgPage {
  total: number;
  messages: Msg[];
}

export function MsgView({ initial }: { initial?: MsgPage }) {
  const { user, ready } = useAuth();
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(initial?.total ?? 0);
  const [msgs, setMsgs] = useState<Msg[]>(initial?.messages ?? []);
  const [loading, setLoading] = useState(!initial);
  const hydratedRef = useRef(Boolean(initial));
  const [content, setContent] = useState("");
  const [preview, setPreview] = useState(false);
  const [postMsg, setPostMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [resentMsg, setResentMsg] = useState("");

  const load = useCallback((p: number, scroll = false) => {
    setLoading(true);
    api(`/api/public/messages?page=${p}`)
      .then((d) => {
        setTotal(d.total || 0);
        setMsgs(d.messages || []);
        setLoading(false);
        if (scroll) window.scrollTo({ top: 0, behavior: "smooth" });
      })
      .catch(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (hydratedRef.current) {
      hydratedRef.current = false; // SSR 首屏已注入，跳过首次拉取
      return;
    }
    load(1);
  }, [load]);

  const submit = async () => {
    const text = content.trim();
    if (!text) {
      setPostMsg({ text: "先写点内容再发～", ok: false });
      return;
    }
    setBusy(true);
    setPostMsg({ text: "发布中…", ok: true });
    try {
      const r = await post("/api/public/messages", { content: text });
      setPostMsg({ text: r.message || (r.ok ? "留言成功！" : "发布失败"), ok: r.ok });
      if (r.ok) {
        setContent("");
        setPreview(false);
        setPage(1);
        load(1);
      }
    } catch (e: any) {
      setPostMsg({ text: e.message || "发布失败", ok: false });
    } finally {
      setBusy(false);
    }
  };

  const resendVerify = async () => {
    setResentMsg("发送中…");
    try {
      const r = await post("/api/auth/resend-verify");
      setResentMsg(r.message || (r.ok ? "已重新发送验证邮件" : "发送失败"));
    } catch (e: any) {
      setResentMsg(e.message || "发送失败");
    }
  };

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const canPost = ready && user && user.emailVerified;

  return (
    <div className="fade-up">
      <div className="card glow-card" style={{ padding: "16px 18px", marginBottom: 14 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 10 }}>
          <b style={{ fontSize: 16 }}>💬 留言板 · 大胆评价</b>
          <span style={{ color: "var(--muted)", fontSize: 13 }}>{total ? `共 ${fmtNum(total)} 条留言` : ""}</span>
        </div>
        <div
          style={{
            fontSize: 13,
            lineHeight: 1.9,
            color: "var(--danger)",
            background: "rgba(248,113,113,0.07)",
            border: "1px solid rgba(248,113,113,0.25)",
            borderRadius: 8,
            padding: "10px 14px",
            marginBottom: 12,
          }}
        >
          关于这个游戏，大家心里都有数：官方把玩家当狗耍的种种操作早已不是个例——<b>是游戏在玩我们，而不是我们在玩游戏</b>。
          满天飞的外挂放任不管，却专挑无关痛痒的小事重拳出击，寒的全是真正玩家的心；
          一些早就没了价值的东西，商城里照样明码标价卖给你；
          自己后端连最基本的校验都懒得做，出了事一甩手全赖到玩家头上，令人作呕。
          诸如此类，罄竹难书——这里不删帖、不和谐，<b>大家都可以大胆评价</b>。
        </div>
        {/* 未登录：只读提示 */}
        {ready && !user && (
          <div
            style={{
              fontSize: 13.5,
              color: "var(--muted)",
              background: "var(--hover)",
              borderRadius: 8,
              padding: "12px 14px",
              display: "flex",
              gap: 10,
              alignItems: "center",
              flexWrap: "wrap",
            }}
          >
            🔒 留言板<b style={{ color: "var(--text)" }}>需登录后才能发言</b>（未登录可浏览）。
            <Link href="/login" className="btn" style={{ padding: "5px 14px", fontSize: 13 }}>
              登录
            </Link>
            <Link href="/register" className="btn btn-primary" style={{ padding: "5px 14px", fontSize: 13 }}>
              免费注册
            </Link>
          </div>
        )}

        {/* 已登录但邮箱未验证 */}
        {ready && user && !user.emailVerified && (
          <div
            style={{
              fontSize: 13.5,
              color: "var(--warn)",
              background: "rgba(251,191,36,0.08)",
              border: "1px solid rgba(251,191,36,0.3)",
              borderRadius: 8,
              padding: "12px 14px",
            }}
          >
            {user.email ? (
              <>
                ✉️ 发言前请先<b>验证邮箱</b>（验证邮件已发到 {user.email}）。
                <button onClick={resendVerify} style={{ marginLeft: 10, padding: "4px 12px", fontSize: 12.5 }}>
                  重发验证邮件
                </button>
                {resentMsg && <span style={{ marginLeft: 8, fontSize: 12.5 }}>{resentMsg}</span>}
              </>
            ) : (
              <>
                ✉️ 你的账号还<b>没有绑定邮箱</b>，请先到个人中心绑定并完成验证后再发言。
                <Link href="/account" className="btn" style={{ marginLeft: 10, padding: "4px 12px", fontSize: 12.5 }}>
                  去绑定邮箱 →
                </Link>
              </>
            )}
          </div>
        )}

        {/* 已登录且已验证：只填内容 */}
        {canPost && (
          <>
            {preview ? (
              <div className="card" style={{ padding: "12px 14px", minHeight: 90 }}>
                {content.trim() ? <Markdown text={content} /> : <span style={{ color: "var(--muted)" }}>（预览为空）</span>}
              </div>
            ) : (
              <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                maxLength={MAX_CONTENT}
                rows={4}
                placeholder={`以「${user.username}」的身份说点什么…（最多 ${MAX_CONTENT} 字，支持 Markdown）`}
                style={{ width: "100%", resize: "vertical" }}
              />
            )}
            <div style={{ display: "flex", gap: 10, alignItems: "center", marginTop: 8, flexWrap: "wrap" }}>
              <span style={{ color: "var(--muted)", fontSize: 12 }}>
                {content.length} / {MAX_CONTENT} · 以 <b>{user.username}</b> 发布 · 支持 Markdown
              </span>
              <span
                style={{ fontSize: 13, flex: 1, color: postMsg ? (postMsg.ok ? "var(--accent)" : "var(--danger)") : undefined }}
              >
                {postMsg?.text || ""}
              </span>
              <button onClick={() => setPreview(!preview)}>{preview ? "✏️ 继续编辑" : "👁 预览"}</button>
              <button className="btn-primary" onClick={submit} disabled={busy}>
                ✍️ 发布留言
              </button>
            </div>
          </>
        )}
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 10 }}>
        <button onClick={() => load(page)}>↻ 刷新</button>
        {total > PAGE_SIZE && (
          <span style={{ marginLeft: "auto", display: "inline-flex", gap: 8, alignItems: "center" }}>
            <button disabled={page <= 1} onClick={() => (setPage(page - 1), load(page - 1, true))}>
              ‹ 上一页
            </button>
            <span style={{ fontSize: 13, color: "var(--muted)" }}>
              {page} / {pages}
            </span>
            <button disabled={page >= pages} onClick={() => (setPage(page + 1), load(page + 1, true))}>
              下一页 ›
            </button>
          </span>
        )}
      </div>

      {loading && (
        <div style={{ textAlign: "center", padding: 30 }}>
          <span className="spinner" />
        </div>
      )}
      {!loading && !msgs.length && (
        <div className="card card-pad" style={{ textAlign: "center", color: "var(--muted)", padding: 40 }}>
          还没有留言，来抢沙发！
        </div>
      )}
      {msgs.map((m) => (
        <div key={m.id} className="card fade-up" style={{ padding: "14px 18px", marginBottom: 12 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
            {m.pinned && (
              <span className="badge" style={{ background: "rgba(251,191,36,0.14)", color: "var(--warn)" }}>
                📌 置顶
              </span>
            )}
            {(m.mergedCount ?? 0) > 0 && (
              <span
                className="badge"
                style={{ background: "rgba(96,165,250,0.13)", color: "var(--primary-strong)" }}
                title="AI 整理：多条高度相似的刷屏留言已合并为这一条"
              >
                🧹 已合并 {m.mergedCount} 条相似留言
              </span>
            )}
            <b>{m.nickname || "游客"}</b>
            <span style={{ color: "var(--muted)", fontSize: 12 }}>
              #{m.id} · {fmtTime(m.createdAt)} (北京时间)
            </span>
          </div>
          <div style={{ marginTop: 8 }}>
            <Markdown text={m.content} />
          </div>
          {m.reply && (
            <div
              style={{
                marginTop: 10,
                padding: "10px 12px",
                background: "rgba(52,211,153,0.08)",
                border: "1px solid rgba(52,211,153,0.25)",
                borderRadius: 8,
              }}
            >
              <div style={{ fontSize: 12, fontWeight: 700, color: "var(--accent)", marginBottom: 4 }}>
                📣 管理员回复{m.replyAt ? ` · ${fmtTime(m.replyAt)}` : ""}
              </div>
              <Markdown text={m.reply} />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
