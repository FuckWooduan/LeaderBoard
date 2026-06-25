import type { Metadata } from "next";

/**
 * 公开 API 文档页（自托管 Swagger UI）。
 *
 * 关键设计——「缓存容错」：本页是纯 Server Component，靠一段内联 <script> 启动 Swagger UI，
 * 只引用 /public 下的稳定地址（/swagger/*、/openapi.yaml），不依赖任何「随每次构建变哈希」的
 * Next 客户端 chunk。即使某层（CF/nginx/Next ISR）把这份 HTML 缓存住，重新部署后 chunk 哈希
 * 轮换也不会让本页 404 变白板——内联脚本照常从稳定地址加载并挂载 Swagger UI。
 *
 * 同时声明 force-dynamic + revalidate=0，正常情况下不预渲染、不发 s-maxage（配合 next.config
 * 的 no-store），让边缘每次回源拿最新；缓存容错只是兜底。
 */

export const dynamic = "force-dynamic";
export const revalidate = 0;

export const metadata: Metadata = {
  title: "公开 API 文档 | 生死狙击排行榜 example.com",
  description:
    "example.com 全站数据开放 API：排行榜、玩家查询、武器库、分区榜、活动等接口文档。",
  robots: "index,follow",
  alternates: { canonical: "/api-docs" },
};

// 内联启动脚本：随 SSR 直出，浏览器解析即执行，不依赖 React hydration / 页面 chunk。
const BOOTSTRAP = `
(function () {
  var mount = document.getElementById("swagger-ui-root");
  if (!mount) return;
  // 文档页本地禁用平滑滚动，避免与 deep-link 锚点定位打架
  try { document.documentElement.style.scrollBehavior = "auto"; } catch (e) {}

  function fail() {
    mount.innerHTML =
      '<div style="padding:40px;text-align:center;color:#64748b">' +
      '文档渲染组件加载失败，请直接 <a href="/openapi.yaml" style="color:#2563eb">下载 OpenAPI YAML</a> 查看接口定义。' +
      "</div>";
  }

  var css = document.createElement("link");
  css.rel = "stylesheet";
  css.href = "/swagger/swagger-ui.css";
  document.head.appendChild(css);

  var s = document.createElement("script");
  s.src = "/swagger/swagger-ui-bundle.js";
  s.async = true;
  s.onload = function () {
    var B = window.SwaggerUIBundle;
    if (!B) { fail(); return; }
    B({
      url: "/openapi.yaml",
      domNode: mount,
      deepLinking: true,
      docExpansion: "list",
      defaultModelsExpandDepth: 0,
      tryItOutEnabled: true,
      presets: [B.presets.apis],
    });
  };
  s.onerror = fail;
  document.body.appendChild(s);
})();
`;

export default function ApiDocsPage() {
  const linkStyle = { color: "rgba(255,255,255,0.92)", fontSize: 13.5 } as const;
  return (
    <div style={{ background: "#fff", minHeight: "100dvh", color: "#1e293b" }}>
      <div
        style={{
          background: "#2563eb",
          color: "#fff",
          padding: "12px 20px",
          display: "flex",
          alignItems: "center",
          gap: 18,
          flexWrap: "wrap",
        }}
      >
        <b style={{ fontSize: 16 }}>📘 公开 API 文档</b>
        <a href="/" style={linkStyle}>
          ← 返回首页
        </a>
        <a href="/openapi.yaml" style={linkStyle}>
          ⬇ 下载 OpenAPI YAML
        </a>
        <a href="/mcp" style={linkStyle}>
          MCP 端点
        </a>
      </div>
      <div id="swagger-ui-root" suppressHydrationWarning />
      <script dangerouslySetInnerHTML={{ __html: BOOTSTRAP }} />
    </div>
  );
}
