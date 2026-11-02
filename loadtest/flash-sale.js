// Flash sale: hundreds of stores order the same scarce product at once, each
// together with a plentiful one, and pay by card. At the end the script checks
// that the warehouse sold exactly what it had and never went below zero.
//
//   docker compose --profile loadtest run --rm k6

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8091';
const STORES = Number(__ENV.STORES || 400);
const UNITS = Number(__ENV.UNITS || 150);
// Latency targets for a developer machine. CI raises them: its shared 2-vCPU
// runner hosts all fifteen containers, and only correctness must hold there.
const PLACE_P95 = Number(__ENV.PLACE_P95_MS || 800);
const PAY_P95 = Number(__ENV.PAY_P95_MS || 800);

const confirmed = new Counter('surtido_orders_confirmed');
const rejected = new Counter('surtido_orders_rejected');
const sagaTime = new Trend('surtido_time_to_confirm_seconds');

export const options = {
  scenarios: {
    flash: { executor: 'per-vu-iterations', vus: STORES, iterations: 1, maxDuration: '5m' },
  },
  thresholds: {
    'http_req_duration{name:place_order}': [`p(95)<${PLACE_P95}`],
    'http_req_duration{name:pay}': [`p(95)<${PAY_P95}`],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

const json = { headers: { 'content-type': 'application/json' } };
const get = (path, name) => http.get(`${BASE}/api${path}`, { tags: { name } });
const post = (path, body, name) => http.post(`${BASE}/api${path}`, JSON.stringify(body), { ...json, tags: { name } });

export function setup() {
  const products = get('/products', 'setup').json();
  const scarce = products.find((p) => p.sku === 'BEB-ISO-500');
  const plenty = products.find((p) => p.sku === 'BEB-AGU-1L');
  // Make sure exactly UNITS more scarce units exist than before, and plenty of water.
  post(`/products/${scarce.id}/restock`, { quantity: UNITS }, 'setup');
  post(`/products/${plenty.id}/restock`, { quantity: STORES * 2 }, 'setup');
  const available = get(`/products/${scarce.id}`, 'setup').json('stock');
  const stores = get('/stores', 'setup').json();
  return { scarce: scarce.id, plenty: plenty.id, available, stores: stores.map((s) => s.id) };
}

export default function (data) {
  sleep(Math.random() * 2);
  const started = Date.now();
  const storeId = data.stores[__VU % data.stores.length];
  const placed = post('/orders', {
    storeId,
    paymentMethod: 'CARD',
    lines: [
      { productId: data.plenty, quantity: 2 },
      { productId: data.scarce, quantity: 1 },
    ],
  }, 'place_order');
  if (!check(placed, { 'order accepted for processing': (r) => r.status === 202 })) return;
  const id = placed.json('id');

  let status = 'PENDING_STOCK';
  for (let i = 0; i < 60 && status === 'PENDING_STOCK'; i++) {
    sleep(0.5);
    status = get(`/orders/${id}`, 'order_status').json('status');
  }
  if (status === 'REJECTED') {
    rejected.add(1);
    return;
  }
  check(status, { 'stock reserved': (s) => s === 'AWAITING_PAYMENT' });

  // The payment opens asynchronously once stock is reserved; until then it is a 404, which is expected here.
  let open = false;
  for (let i = 0; i < 40 && !open; i++) {
    const response = http.get(`${BASE}/api/payments/order/${id}`, {
      tags: { name: 'payment_status' },
      responseCallback: http.expectedStatuses(200, 404),
    });
    open = response.status === 200;
    if (!open) sleep(0.25);
  }
  if (!check(open, { 'payment opened': (o) => o })) return;

  const response = post(`/payments/order/${id}/card`, {
    number: '4242424242424242', expMonth: 12, expYear: 2030, cvc: '123', holderName: 'Carga k6',
  }, 'pay');
  const paid = check(response, { 'card approved': (r) => r.status === 200 && r.json('result') === 'approved' });
  if (!paid) return;

  for (let i = 0; i < 60 && status !== 'CONFIRMED'; i++) {
    sleep(0.5);
    status = get(`/orders/${id}`, 'order_status').json('status');
  }
  if (check(status, { 'order confirmed': (s) => s === 'CONFIRMED' })) {
    confirmed.add(1);
    sagaTime.add((Date.now() - started) / 1000);
  }
}

export function teardown(data) {
  sleep(3);
  const stock = get(`/products/${data.scarce}`, 'teardown').json('stock');
  const sold = data.available - stock;
  check(null, {
    'stock never below zero': () => stock >= 0,
    'sold out completely': () => STORES <= data.available || stock === 0,
  });
  console.log(`Scarce product: ${data.available} available before the sale, ${stock} left, ${sold} sold.`);
}
