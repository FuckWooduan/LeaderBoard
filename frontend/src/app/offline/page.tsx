export const metadata = { title: "当前离线 | example.com", robots: "noindex" };

export default function OfflinePage() {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "center", minHeight: "80dvh", padding: 20 }}>
      <div className="card glow-card" style={{ maxWidth: 420, padding: "38px 34px", textAlign: "center" }}>
        <div style={{ fontSize: 46, marginBottom: 12 }}>📡</div>
        <h1 style={{ margin: "0 0 8px", fontSize: 22 }}>当前离线</h1>
        <p style={{ color: "var(--muted)", fontSize: 14, lineHeight: 1.8 }}>
          网络连接不可用。已缓存的页面仍可浏览，恢复网络后会自动取到最新数据。
        </p>
        <a href="/" className="btn btn-primary" style={{ display: "inline-block", marginTop: 14 }}>
          重试
        </a>
      </div>
    </div>
  );
}
