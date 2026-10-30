// Сервис-воркер: держит оболочку приложения в кэше, чтобы экран открывался мгновенно.
// Данные (API, видео) всегда берутся с сервера.
const CACHE = "clipper-v1";
const SHELL = ["/", "/static/app.css", "/static/app.js", "/manifest.webmanifest",
               "/static/icons/icon-192.png", "/static/icons/icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  const shell = url.pathname === "/" || url.pathname.startsWith("/static/") || url.pathname === "/manifest.webmanifest";
  if (!shell) return; // API, /media, /clips — только сеть
  // Сначала сеть (чтобы обновления приходили сразу), при отсутствии сети — кэш.
  e.respondWith(fetch(e.request).then((r) => {
    if (r.ok && !r.redirected) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
    return r;
  }).catch(() => caches.match(e.request)));
});
