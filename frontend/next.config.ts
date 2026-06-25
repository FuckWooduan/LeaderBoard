import type { NextConfig } from "next";

/**
 * SSR 模式（standalone）：Next 以独立 Node 服务运行（生产 127.0.0.1:3000），
 * nginx 把页面流量反代到 Next、/api 流量反代到 Spring(8080)。
 * 页面首屏数据在服务端直连 Spring 获取（见 lib/api.ts 的 serverApi）。
 */
const nextConfig: NextConfig = {
  output: "standalone",
  images: { unoptimized: true },
  // 开发模式下把 API 代理到本地 Spring（生产由 nginx 分流，不经 Next）
  async rewrites() {
    return process.env.NODE_ENV === "development"
      ? [{ source: "/api/:path*", destination: "http://127.0.0.1:8080/api/:path*" }]
      : [];
  },
  // 旧版静态站的 *.html 地址 → 新路由（收藏夹/外链/搜索引擎已收录页无缝迁移）
  async redirects() {
    return [
      { source: "/index.html", destination: "/", permanent: true },
      { source: "/admin.html", destination: "/admin", permanent: true },
      { source: "/admin-boards.html", destination: "/admin/boards", permanent: true },
      { source: "/admin-fields.html", destination: "/admin/fields", permanent: true },
      { source: "/admin-apikey.html", destination: "/admin/apikey", permanent: true },
      { source: "/admin-messages.html", destination: "/admin/messages", permanent: true },
      { source: "/api-docs.html", destination: "/api-docs", permanent: true },
      { source: "/api-server.html", destination: "/api-docs", permanent: true },
      { source: "/offline.html", destination: "/offline", permanent: true },
    ];
  },
  async headers() {
    return [
      {
        source: "/:path(register|login|account|forgot|reset|verify)",
        headers: [
          { key: "Cache-Control", value: "no-store, no-cache, must-revalidate, max-age=0" },
        ],
      },
      {
        // 后台页（静态壳）永不缓存：避免新增/改动后台功能后浏览器仍渲染旧导航/旧页（需手动清缓存才更新）。
        source: "/admin/:path*",
        headers: [
          { key: "Cache-Control", value: "no-store, no-cache, must-revalidate, max-age=0" },
        ],
      },
      {
        source: "/admin",
        headers: [
          { key: "Cache-Control", value: "no-store, no-cache, must-revalidate, max-age=0" },
        ],
      },
      {
        source: "/sw.js",
        headers: [
          { key: "Cache-Control", value: "no-store, no-cache, must-revalidate, max-age=0" },
        ],
      },
      {
        // API 文档页与 OpenAPI 规范永不缓存：随每次部署更新（HTML 不该被边缘缓存住旧版）。
        source: "/api-docs",
        headers: [
          { key: "Cache-Control", value: "no-store, no-cache, must-revalidate, max-age=0" },
        ],
      },
      {
        source: "/openapi.yaml",
        headers: [
          { key: "Cache-Control", value: "no-store, no-cache, must-revalidate, max-age=0" },
        ],
      },
      {
        source: "/ruffle/:path*",
        headers: [
          { key: "Cache-Control", value: "public, max-age=31536000, immutable" },
        ],
      },
    ];
  },
};

export default nextConfig;
