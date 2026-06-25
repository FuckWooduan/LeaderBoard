"use client";

import { useEffect, type ReactNode } from "react";
import { createPortal } from "react-dom";

/**
 * 把浮层渲染到 {@code document.body}，绕开任何带 transform/filter/will-change 的祖先。
 * 否则 {@code position:fixed} 会以那个祖先为包含块（如 .fade-up 动画残留的 identity transform），
 * 导致灯箱/弹窗被定位到页面深处——移动端滚动后点开只看到一片灰色遮罩、图片在视口外。
 */
function portal(node: ReactNode): ReactNode {
  return typeof document === "undefined" ? null : createPortal(node, document.body);
}

export function Modal({
  open,
  onClose,
  children,
  maxWidth = 560,
}: {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  maxWidth?: number;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;
  return portal(
    <div
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 100,
        background: "rgba(2,6,23,0.66)",
        backdropFilter: "blur(4px)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 20,
      }}
    >
      <div
        className="card fade-up"
        style={{ maxWidth, width: "100%", maxHeight: "80vh", overflow: "auto", padding: "20px 22px", position: "relative" }}
      >
        <button
          onClick={onClose}
          aria-label="关闭"
          style={{ position: "absolute", top: 10, right: 12, border: "none", background: "none", fontSize: 18, color: "var(--muted)" }}
        >
          ✕
        </button>
        {children}
      </div>
    </div>
  );
}

/** 图片灯箱。 */
export function Lightbox({ src, onClose }: { src: string | null; onClose: () => void }) {
  useEffect(() => {
    if (!src) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [src, onClose]);

  if (!src) return null;
  return portal(
    <div
      onClick={onClose}
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 110,
        background: "rgba(2,6,23,0.85)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 16,
        cursor: "zoom-out",
        overflow: "auto",
      }}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={src} alt="预览" style={{ maxWidth: "96vw", maxHeight: "94vh", borderRadius: 10 }} />
    </div>
  );
}
