"use client";

import { fmtNum } from "@/lib/format";

export function Pager({
  page,
  totalPages,
  total,
  unit = "条",
  onPage,
}: {
  page: number;
  totalPages: number;
  total: number;
  unit?: string;
  onPage: (p: number) => void;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", padding: "12px 2px" }}>
      <span style={{ color: "var(--muted)", fontSize: 13 }}>
        第 {page} / {totalPages} 页 · 共 {fmtNum(total)} {unit}
      </span>
      <button disabled={page <= 1} onClick={() => onPage(page - 1)}>
        ‹ 上一页
      </button>
      <button disabled={page >= totalPages} onClick={() => onPage(page + 1)}>
        下一页 ›
      </button>
    </div>
  );
}
