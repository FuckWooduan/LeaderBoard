"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { post } from "@/lib/api";
import { useAuth } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default function VerifyPage() {
  const params = useSearchParams();
  const { refresh } = useAuth();
  const [state, setState] = useState<"loading" | "ok" | "fail">("loading");
  const [msg, setMsg] = useState("正在验证…");

  useEffect(() => {
    const token = params.get("token") || "";
    if (!token) {
      setState("fail");
      setMsg("缺少验证 token");
      return;
    }
    post("/api/auth/verify-email", { token })
      .then((r) => {
        if (r.ok) {
          setState("ok");
          setMsg(r.message || "邮箱验证成功！");
          void refresh();
        } else {
          setState("fail");
          setMsg(r.message || "验证失败");
        }
      })
      .catch((e) => {
        setState("fail");
        setMsg(e.message || "验证失败");
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="card glow-card fade-up" style={{ maxWidth: 460, margin: "60px auto", padding: "40px 32px", textAlign: "center" }}>
      <div style={{ fontSize: 46, marginBottom: 12 }}>
        {state === "loading" ? <span className="spinner" /> : state === "ok" ? "✅" : "⚠️"}
      </div>
      <h1 style={{ fontSize: 22, margin: "0 0 8px" }}>
        {state === "ok" ? "邮箱已验证" : state === "fail" ? "验证未成功" : "验证中"}
      </h1>
      <p style={{ color: "var(--muted)", fontSize: 14, lineHeight: 1.8 }}>{msg}</p>
      {state !== "loading" && (
        <div style={{ display: "flex", gap: 10, justifyContent: "center", marginTop: 18 }}>
          <Link href="/msg" className="btn btn-primary">
            去留言板
          </Link>
          <Link href="/" className="btn">
            回首页
          </Link>
        </div>
      )}
    </div>
  );
}
