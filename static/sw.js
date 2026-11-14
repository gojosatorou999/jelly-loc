// Jelly Loc PWA Service Worker
// App shell is network-first (so deploys show up immediately) with cache fallback for offline.
const CACHE_NAME = 'jelly-loc-v3';
const TILE_CACHE = 'jelly-loc-tiles';
const STATIC_ASSETS = [
  '/',
  '/static/styles.css?v=3',
  '/static/app.js?v=3',
  '/manifest.json',
  '/static/icons/icon-192.png',
  '/static/icons/icon-512.png',
  '/static/icons/apple-touch-icon.png',
  '/static/icons/favicon.png',
  '/static/icons/icon.svg',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
  'https://fonts.googleapis.com/css2?family=Outfit:wght@400;500;600;700;800&display=swap',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => Promise.allSettled(STATIC_ASSETS.map((u) => cache.add(u))))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME && k !== TILE_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return; // let POST/DELETE go straight to the network
  const url = new URL(req.url);

  // API: always network, never cached
  if (url.origin === self.location.origin && url.pathname.startsWith('/api/')) return;

  // Map tiles: stale-while-revalidate
  if (url.hostname === 'server.arcgisonline.com' || url.hostname.endsWith('tile.openstreetmap.org')) {
    event.respondWith(
      caches.open(TILE_CACHE).then((cache) =>
        cache.match(req).then((cached) => {
          const network = fetch(req).then((res) => {
            if (res.ok) cache.put(req, res.clone());
            return res;
          }).catch(() => cached);
          return cached || network;
        })
      )
    );
    return;
  }

  // App shell & libraries: network-first, fall back to cache when offline
  event.respondWith(
    fetch(req).then((res) => {
      if (res && res.ok && (url.origin === self.location.origin || url.hostname === 'unpkg.com')) {
        const copy = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
      }
      return res;
    }).catch(() =>
      caches.match(req).then((cached) => cached || (req.mode === 'navigate' ? caches.match('/') : Response.error()))
    )
  );
});
