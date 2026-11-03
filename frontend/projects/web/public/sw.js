/*
 * FoodStack service worker (TDD 8.3, 14): keeps the app shell for a rider who loses signal, and
 * shows push messages from FCM. API calls always go to the network.
 */
const SHELL = 'foodstack-shell-v1';

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(SHELL)
      .then((cache) => cache.add(new Request(self.registration.scope, { cache: 'reload' })))
      .catch(() => undefined)
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// Pages: network first, the saved shell when offline. Hashed bundles: cache first.
self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.includes('/api/')) return;
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(SHELL).then((cache) => cache.put(self.registration.scope, copy));
          return response;
        })
        .catch(() => caches.match(self.registration.scope)),
    );
    return;
  }
  if (/-[A-Za-z0-9_-]{8}\.(js|css)$/.test(url.pathname) || /\.(woff2?)$/.test(url.pathname)) {
    event.respondWith(
      caches.match(request).then(
        (hit) =>
          hit ||
          fetch(request).then((response) => {
            if (response.ok) {
              const copy = response.clone();
              caches.open(SHELL).then((cache) => cache.put(request, copy));
            }
            return response;
          }),
      ),
    );
  }
});

self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    payload = { notification: { title: event.data ? event.data.text() : 'FoodStack' } };
  }
  const notification = payload.notification || {};
  const data = payload.data || {};
  const link =
    (payload.fcmOptions && payload.fcmOptions.link) ||
    (notification.click_action) ||
    (data.link ? new URL(data.link.replace(/^\//, ''), self.registration.scope).href : null) ||
    self.registration.scope;
  const urgent = data.type === 'rider-offer';
  event.waitUntil(
    self.registration.showNotification(notification.title || 'FoodStack', {
      body: notification.body || '',
      icon: new URL('icon-192.png', self.registration.scope).href,
      badge: new URL('icon-192.png', self.registration.scope).href,
      tag: data.type || undefined,
      renotify: urgent,
      requireInteraction: urgent,
      vibrate: urgent ? [300, 120, 300, 120, 300] : undefined,
      data: { link },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const link = (event.notification.data && event.notification.data.link) || self.registration.scope;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      for (const client of windows) {
        if (client.url.startsWith(self.registration.scope) && 'focus' in client) {
          client.navigate(link).catch(() => undefined);
          return client.focus();
        }
      }
      return self.clients.openWindow(link);
    }),
  );
});
