"use client";

/** 留言板 Markdown 渲染：GFM + 链接新窗口；不允许内嵌 HTML（react-markdown 默认即不渲染 HTML，天然防 XSS）。 */

import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function Markdown({ text }: { text: string }) {
  return (
    <div className="md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noopener noreferrer nofollow">
              {children}
            </a>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}
