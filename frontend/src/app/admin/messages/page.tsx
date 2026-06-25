"use client";

/** 后台 · 留言板管理（与旧版 admin-messages.html 等价 + Markdown 预览）。 */

import { useCallback, useEffect, useState } from "react";
import { adminApi, adminPost } from "@/lib/api";
import { fmtNum, fmtTime } from "@/lib/format";
import { AdminShell, useToast } from "@/components/AdminShell";
import { Markdown } from "@/components/Markdown";
import { Modal } from "@/components/Modal";

const PAGE_SIZE = 50;

interface AdminMsg {
  id: number;
  nickname?: string;
  content: string;
  ip?: string;
  email?: string;
  createdAt: number;
  visible: boolean;
  pinned: boolean;
  reply?: string;
  replyAt?: number;
  reviewStatus?: string;
  reviewReason?: string;
  mergedCount?: number;
}

const REVIEW_BADGE: Record<string, [string, string]> = {
  PENDING: ["待审核", "var(--warn)"],
  PASS: ["AI通过", "var(--accent)"],
  REJECTED: ["AI拒绝", "var(--danger)"],
  SKIPPED: ["跳过审核", "var(--muted)"],
  MERGED: ["已被合并", "var(--primary-strong)"],
};

export default function AdminMessagesPage() {
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [msgs, setMsgs] = useState<AdminMsg[]>([]);
  const [replyFor, setReplyFor] = useState<AdminMsg | null>(null);
  const [replyText, setReplyText] = useState("");
  const [housekeeping, setHousekeeping] = useState(false);
  const [toastNode, toast] = useToast();

  const load = useCallback((p: number) => {
    adminApi(`/api/boards/messages?page=${p}&size=${PAGE_SIZE}`)
      .then((d) => {
        setTotal(d.total || 0);
        setMsgs(d.messages || []);
      })
      .catch(() => {});
  }, []);

  useEffect(() => load(page), [load, page]);

  const action = async (id: number, act: string, text?: string) => {
    try {
      const r = await adminPost("/api/boards/messages/action", { id, action: act, text });
      if (r.ok) {
        toast("已操作");
        load(page);
      } else {
        toast(r.message || "操作失败", false);
      }
    } catch (e: any) {
      toast(`操作失败：${e.message}`, false);
    }
  };

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <AdminShell title="💬 留言板管理">
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 14, flexWrap: "wrap" }}>
        <span style={{ color: "var(--muted)", fontSize: 13 }}>共 {fmtNum(total)} 条（含隐藏）</span>
        <button onClick={() => load(page)}>↻ 刷新</button>
        <button
          disabled={housekeeping}
          title="AI 检查最新 200 条留言并合并刷屏（每小时也会自动跑一轮；被改动的留言人会收到邮件）"
          onClick={async () => {
            setHousekeeping(true);
            try {
              const r = await adminPost("/api/boards/messages/housekeep");
              toast(r.message || "整理完成");
              load(page);
            } catch (e: any) {
              toast(`整理失败：${e.message}`, false);
            } finally {
              setHousekeeping(false);
            }
          }}
        >
          {housekeeping ? "整理中…（AI 检查可能需要 1-2 分钟）" : "🧹 立即整理刷屏"}
        </button>
        {total > PAGE_SIZE && (
          <span style={{ marginLeft: "auto", display: "inline-flex", gap: 8, alignItems: "center" }}>
            <button disabled={page <= 1} onClick={() => setPage(page - 1)}>
              ‹ 上一页
            </button>
            <span style={{ fontSize: 13, color: "var(--muted)" }}>
              {page} / {pages}
            </span>
            <button disabled={page >= pages} onClick={() => setPage(page + 1)}>
              下一页 ›
            </button>
          </span>
        )}
      </div>

      {msgs.map((m) => {
        const rb = REVIEW_BADGE[m.reviewStatus || ""] || null;
        return (
          <div
            key={m.id}
            className="card"
            style={{ padding: "14px 18px", marginBottom: 12, opacity: m.visible ? 1 : 0.55 }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
              {m.pinned && (
                <span className="badge" style={{ background: "rgba(251,191,36,0.14)", color: "var(--warn)" }}>
                  📌 置顶
                </span>
              )}
              {!m.visible && (
                <span className="badge" style={{ background: "var(--hover)", color: "var(--muted)" }}>
                  已隐藏
                </span>
              )}
              {rb && (
                <span className="badge" style={{ background: "var(--hover)", color: rb[1] }}>
                  {rb[0]}
                </span>
              )}
              {(m.mergedCount ?? 0) > 0 && (
                <span className="badge" style={{ background: "rgba(96,165,250,0.13)", color: "var(--primary-strong)" }}>
                  🧹 吸收了 {m.mergedCount} 条
                </span>
              )}
              <b>{m.nickname || "游客"}</b>
              <span style={{ color: "var(--muted)", fontSize: 12 }}>
                #{m.id} · {fmtTime(m.createdAt)} · IP {m.ip || "—"} · {m.email || "无邮箱"}
              </span>
            </div>
            <div style={{ marginTop: 8 }}>
              <Markdown text={m.content} />
            </div>
            {m.reviewReason && (
              <div style={{ marginTop: 6, fontSize: 12.5, color: "var(--warn)" }}>AI 审核备注：{m.reviewReason}</div>
            )}
            {m.reply && (
              <div
                style={{
                  marginTop: 10,
                  padding: "8px 12px",
                  background: "rgba(52,211,153,0.08)",
                  border: "1px solid rgba(52,211,153,0.25)",
                  borderRadius: 8,
                }}
              >
                <div style={{ fontSize: 12, fontWeight: 700, color: "var(--accent)", marginBottom: 4 }}>
                  📣 已回复{m.replyAt ? ` · ${fmtTime(m.replyAt)}` : ""}
                </div>
                <Markdown text={m.reply} />
              </div>
            )}
            <div style={{ display: "flex", gap: 6, marginTop: 10, flexWrap: "wrap" }}>
              <button style={{ padding: "4px 10px", fontSize: 12.5 }} onClick={() => action(m.id, m.pinned ? "unpin" : "pin")}>
                {m.pinned ? "取消置顶" : "置顶"}
              </button>
              <button style={{ padding: "4px 10px", fontSize: 12.5 }} onClick={() => action(m.id, m.visible ? "hide" : "show")}>
                {m.visible ? "隐藏" : "恢复显示"}
              </button>
              <button
                style={{ padding: "4px 10px", fontSize: 12.5 }}
                onClick={() => {
                  setReplyFor(m);
                  setReplyText(m.reply || "");
                }}
              >
                {m.reply ? "改回复" : "回复"}
              </button>
              {m.reply && (
                <button style={{ padding: "4px 10px", fontSize: 12.5 }} onClick={() => action(m.id, "reply", "")}>
                  清除回复
                </button>
              )}
              <button
                className="btn-danger"
                style={{ padding: "4px 10px", fontSize: 12.5, marginLeft: "auto" }}
                onClick={() => confirm(`确认物理删除 #${m.id}？不可恢复。`) && action(m.id, "delete")}
              >
                删除
              </button>
            </div>
          </div>
        );
      })}
      {!msgs.length && (
        <div className="card card-pad" style={{ textAlign: "center", color: "var(--muted)", padding: 40 }}>
          暂无留言
        </div>
      )}

      <Modal open={replyFor !== null} onClose={() => setReplyFor(null)}>
        <div style={{ fontWeight: 700, marginBottom: 10 }}>回复 #{replyFor?.id}（支持 Markdown）</div>
        <textarea
          value={replyText}
          onChange={(e) => setReplyText(e.target.value)}
          rows={5}
          maxLength={2000}
          style={{ width: "100%", resize: "vertical" }}
          placeholder="回复内容…"
        />
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 12 }}>
          <button onClick={() => setReplyFor(null)}>取消</button>
          <button
            className="btn-primary"
            onClick={async () => {
              if (replyFor) {
                await action(replyFor.id, "reply", replyText.trim());
                setReplyFor(null);
              }
            }}
          >
            保存回复
          </button>
        </div>
      </Modal>
      {toastNode}
    </AdminShell>
  );
}
