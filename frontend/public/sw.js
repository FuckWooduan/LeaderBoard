/* StrikeGod Service Worker（Next.js 版）
 * 策略：同源 GET 网络优先、失败回退缓存；导航离线兜底 /offline。
 * - 全部 /api/ 请求与 SSE（text/event-stream）不经 SW（实时数据/鉴权请求不缓存，防 SW 挂起流式响应）。
 * - /admin、/actuator 不缓存。
 */
var CACHE = 'skg-next-v3';
var SHELL = ['/', '/offline', '/manifest.json', '/icon.svg'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL).catch(function () {}); }));
  self.skipWaiting();
});

self.addEventListener('activate', function (e) {
  e.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('message', function (e) {
  if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});

self.addEventListener('fetch', function (e) {
  var req = e.request;
  if (req.method !== 'GET') return;
  var url = new URL(req.url);
  if (url.origin !== location.origin) return;
  // 实时/鉴权/流式：完全绕过 SW
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator')) return;
  if ((req.headers.get('accept') || '').indexOf('text/event-stream') >= 0) return;
  if (url.pathname.startsWith('/admin')) return;
  if (url.pathname.startsWith('/ruffle/') || url.pathname.startsWith('/_next/')) return;
  if (url.pathname === '/register' || url.pathname === '/login' || url.pathname === '/account') return;

  e.respondWith(
    fetch(req).then(function (res) {
      if (res && res.ok) {
        var copy = res.clone();
        caches.open(CACHE).then(function (c) { c.put(req, copy); });
      }
      return res;
    }).catch(function () {
      return caches.match(req).then(function (hit) {
        if (hit) return hit;
        if (req.mode === 'navigate') return caches.match('/offline');
        return Response.error();
      });
    })
  );
});
