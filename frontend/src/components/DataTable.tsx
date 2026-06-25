"use client";

/**
 * 轻量动态表格：列由后端 fields[] 驱动（render 类型见 cells.tsx），
 * 支持前三名行高亮、长文本弹窗、行尾自定义列（如订阅按钮）。
 */

import { type ReactNode, useState } from "react";
import { renderCellContent, type Field, type Row } from "./cells";
import { Modal } from "./Modal";

export function DataTable({
  fields,
  rows,
  total,
  emptyText = "暂无数据",
  extraHeader,
  extraCell,
}: {
  fields: Field[];
  rows: Row[];
  total: number;
  emptyText?: string;
  /** 额外列表头（如「订阅」）。 */
  extraHeader?: ReactNode;
  /** 额外列单元格渲染。 */
  extraCell?: (row: Row) => ReactNode;
}) {
  const [longText, setLongText] = useState<string | null>(null);

  if (!rows.length) {
    return (
      <div className="card card-pad" style={{ textAlign: "center", color: "var(--muted)", padding: 48 }}>
        {emptyText}
      </div>
    );
  }

  return (
    <div className="table-wrap card fade-up">
      <table className="data">
        <thead>
          <tr>
            {fields.map((f) => (
              <th key={f.key}>{f.label}</th>
            ))}
            {extraHeader != null && <th>{extraHeader}</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => {
            // 前三高亮按 medal_rank（汇总/龙虎榜=全局位次），无则回退真实 rank
            const mr = typeof row.medal_rank === "number" ? row.medal_rank : row.rank;
            const topCls = mr >= 1 && mr <= 3 ? `top${mr}` : undefined;
            return (
              <tr key={i} className={topCls}>
                {fields.map((f) => {
                  const { node, className } = renderCellContent(f, row, total, setLongText);
                  return (
                    <td key={f.key} className={className}>
                      {node}
                    </td>
                  );
                })}
                {extraCell != null && <td>{extraCell(row)}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>
      <Modal open={longText !== null} onClose={() => setLongText(null)}>
        <div style={{ whiteSpace: "pre-wrap", wordBreak: "break-word", lineHeight: 1.7 }}>{longText}</div>
      </Modal>
    </div>
  );
}
