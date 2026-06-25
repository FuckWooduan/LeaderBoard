import type { Metadata, Viewport } from "next";
import Script from "next/script";
import "./globals.css";
import { AuthProvider } from "@/lib/auth";

export const metadata: Metadata = {
  title: "生死狙击排行榜 · 战力/天梯/战队/分区榜 + 玩家查询 + 武器库 | example.com",
  description:
    "example.com —— 生死狙击玩家自建的实时排行榜与查询站：战力榜、经验榜、天梯榜、战队榜、分区榜一应俱全，支持玩家在线查询、武器数据库、活动通知邮件订阅，全站数据开放 API。永久免费、公益、离线可用。",
  keywords:
    "生死狙击,生死狙击排行榜,生死狙击战力排行榜,战力榜,经验排行榜,天梯排行榜,战队排行榜,分区排行榜,生死狙击玩家查询,武器查询,皓月神弓,strikegod",
  authors: [{ name: "example.com" }],
  applicationName: "氪佬修仙 · 排行榜",
  robots: "index,follow,max-image-preview:large,max-snippet:-1,max-video-preview:-1",
  metadataBase: new URL("https://example.com"),
  alternates: { canonical: "/" },
  manifest: "/manifest.json",
  icons: { icon: "/icon.svg" },
  openGraph: {
    type: "website",
    siteName: "氪佬修仙 · 生死狙击排行榜",
    title: "生死狙击排行榜 · 战力/天梯/战队/分区榜 + 玩家查询 + 武器库",
    description:
      "生死狙击实时排行榜与玩家查询：战力榜、经验榜、天梯榜、战队榜、分区榜，武器数据库，活动通知订阅，全站开放 API。永久免费、公益、离线可用。",
    url: "https://example.com/",
    locale: "zh_CN",
    images: [
      {
        url: "https://example.com/og-image.png",
        width: 1200,
        height: 630,
        alt: "氪佬修仙 · 生死狙击排行榜 example.com",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "生死狙击排行榜 · 战力/天梯/战队/分区榜 + 玩家查询 + 武器库",
    description:
      "生死狙击实时排行榜与玩家查询：战力榜、天梯榜、战队榜、分区榜，武器库，活动通知，全站开放 API。永久免费、公益。",
    images: ["https://example.com/og-image.png"],
  },
};

export const viewport: Viewport = {
  themeColor: "#0b1020",
  width: "device-width",
  initialScale: 1,
};

const JSON_LD = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebSite",
      "@id": "https://example.com/#website",
      url: "https://example.com/",
      name: "氪佬修仙 · 生死狙击排行榜",
      description:
        "生死狙击实时排行榜与玩家查询站：战力榜、经验榜、天梯榜、战队榜、分区榜，武器数据库，活动通知订阅，全站开放 API。",
      inLanguage: "zh-CN",
      potentialAction: {
        "@type": "SearchAction",
        target: { "@type": "EntryPoint", urlTemplate: "https://example.com/player?q={search_term_string}" },
        "query-input": "required name=search_term_string",
      },
    },
    {
      "@type": "WebApplication",
      "@id": "https://example.com/#app",
      name: "氪佬修仙 · 生死狙击排行榜",
      url: "https://example.com/",
      applicationCategory: "GameApplication",
      operatingSystem: "Web",
      browserRequirements: "Requires JavaScript",
      inLanguage: "zh-CN",
      isAccessibleForFree: true,
      offers: { "@type": "Offer", price: "0", priceCurrency: "CNY" },
      featureList:
        "战力排行榜, 经验排行榜, 天梯排行榜, 战队排行榜, 分区排行榜, 玩家查询, 武器查询, 活动通知订阅, 留言板, 开放 API",
    },
  ],
};

/** 主题初始化（防闪烁，最先执行）。无手动选择时跟随系统明暗，手动切换后尊重保存值。 */
const THEME_INIT = `(function(){try{var t=localStorage.getItem("skg_theme");var sysLight=window.matchMedia&&window.matchMedia("(prefers-color-scheme: light)").matches;if(t==="light"||(!t&&sysLight))document.documentElement.setAttribute("data-theme","light");}catch(e){}})();`;

/** SW 注册 + 自动更新（与旧版行为一致）。 */
const SW_INIT = `
if ('serviceWorker' in navigator) {
  var skgReloading = false;
  navigator.serviceWorker.addEventListener('controllerchange', function () {
    if (skgReloading) return; skgReloading = true; location.reload();
  });
  window.addEventListener('load', function () {
    navigator.serviceWorker.register('/sw.js').then(function (reg) {
      reg.update();
      setInterval(function () { reg.update(); }, 60000);
      document.addEventListener('visibilitychange', function () { if (!document.hidden) reg.update(); });
      reg.addEventListener('updatefound', function () {
        var nw = reg.installing;
        if (!nw) return;
        nw.addEventListener('statechange', function () {
          if (nw.state === 'installed' && navigator.serviceWorker.controller) { nw.postMessage({ type: 'SKIP_WAITING' }); }
        });
      });
    }).catch(function () {});
  });
}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT }} />
        <link rel="preconnect" href="https://challenges.cloudflare.com" crossOrigin="anonymous" />
        <link rel="dns-prefetch" href="https://challenges.cloudflare.com" />
        <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(JSON_LD) }} />
      </head>
      <body>
        <div className="skg-watermark" aria-hidden="true" />
        <AuthProvider>{children}</AuthProvider>
        <Script id="skg-sw" strategy="afterInteractive" dangerouslySetInnerHTML={{ __html: SW_INIT }} />
      </body>
    </html>
  );
}
