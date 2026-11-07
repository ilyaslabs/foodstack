// Customer browsing load test, run before each release (testing strategy): search near a point,
// open a store, read its menu and a delivery quote, like the customer app does. Read-only, so it is safe against a live site, but
// it does count against the site's rate limits: run it from an address the limits allow, or
// against a staging copy.
//
//   docker run --rm -i -e BASE_URL=https://food.example.com -e LAT=31.52 -e LNG=74.34 \
//     grafana/k6:1.8.1 run - < tools/k6/browse.js
//
// Settings (environment): BASE_URL (required), LAT and LNG (a point with live stores around it),
// FULFILMENT (DELIVERY, the default, or PICKUP), PEAK (users at the peak, default 50) and HOLD
// (how long the peak lasts, default 5m).

import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const BASE = (__ENV.BASE_URL || '').replace(/\/$/, '');
const LAT = Number(__ENV.LAT || 31.52);
const LNG = Number(__ENV.LNG || 74.34);
const PEAK = Number(__ENV.PEAK || 50);
const HOLD = __ENV.HOLD || '5m';
const FULFILMENT = __ENV.FULFILMENT || 'DELIVERY';

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      stages: [
        { duration: '1m', target: PEAK },
        { duration: HOLD, target: PEAK },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  // The service levels a release has to keep (PRD, non-functional: reads p95 under 300 ms).
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{step:search}': ['p(95)<300'],
    'http_req_duration{step:store}': ['p(95)<300'],
    'http_req_duration{step:menu}': ['p(95)<300'],
    'http_req_duration{step:quote}': ['p(95)<300'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  if (!BASE) fail('Set BASE_URL, for example -e BASE_URL=https://food.example.com');
  const found = http.get(
    `${BASE}/api/v1/search/stores?lat=${LAT}&lng=${LNG}&fulfilment=${FULFILMENT}&size=20`,
  );
  if (found.status !== 200) fail(`Search answered ${found.status}`);
  const stores = found.json('stores').map((s) => ({ id: s.storeId, slug: s.slug }));
  if (stores.length === 0) fail(`No live store near ${LAT},${LNG} for ${FULFILMENT}: pick a point with stores around it`);
  return { stores };
}

export default function ({ stores }) {
  // a customer looks a little around their own point
  const lat = LAT + (Math.random() - 0.5) * 0.02;
  const lng = LNG + (Math.random() - 0.5) * 0.02;
  const search = http.get(
    `${BASE}/api/v1/search/stores?lat=${lat}&lng=${lng}&fulfilment=${FULFILMENT}&size=20`,
    {
      tags: { step: 'search', name: 'search' },
    },
  );
  check(search, { 'search 200': (r) => r.status === 200 });
  sleep(1 + Math.random() * 2);

  const store = stores[Math.floor(Math.random() * stores.length)];
  const page = http.get(`${BASE}/api/v1/stores/${store.slug}`, {
    tags: { step: 'store', name: 'store page' },
  });
  check(page, { 'store 200': (r) => r.status === 200 });
  const menu = http.get(`${BASE}/api/v1/stores/${store.id}/menu`, {
    tags: { step: 'menu', name: 'menu' },
  });
  check(menu, { 'menu 200': (r) => r.status === 200 });
  const quote = http.get(`${BASE}/api/v1/stores/${store.id}/delivery-quote?lat=${lat}&lng=${lng}`, {
    tags: { step: 'quote', name: 'delivery quote' },
  });
  check(quote, { 'quote 200': (r) => r.status === 200 });
  sleep(2 + Math.random() * 3);
}
