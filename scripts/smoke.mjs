// End-to-end check through the Spring Cloud Gateway, the way a store and the
// warehouse use Surtido. Expects `docker compose up`.
// Usage: node scripts/smoke.mjs

const BASE = process.env.BASE_URL ?? 'http://localhost:8091';
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8026';

async function api(path, { method, body } = {}) {
  const response = await fetch(`${BASE}/api${path}`, {
    method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    const error = new Error(`${response.status} ${path}: ${text.slice(0, 200)}`);
    error.status = response.status;
    throw error;
  }
  return data;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(what, check, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const value = await check();
      if (value) return value;
    } catch {
      // Not there yet.
    }
    await sleep(300);
  }
  throw new Error(`Timed out waiting for ${what}`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

/** Banxico's CLABE check digit. */
function clabeValid(clabe) {
  if (!/^\d{18}$/.test(clabe)) return false;
  const weights = [3, 7, 1];
  let sum = 0;
  for (let i = 0; i < 17; i++) sum += (Number(clabe[i]) * weights[i % 3]) % 10;
  return (10 - (sum % 10)) % 10 === Number(clabe[17]);
}

let failures = 0;
async function step(name, fn) {
  const started = Date.now();
  try {
    await fn();
    console.log(`  ok    ${name} (${Date.now() - started} ms)`);
  } catch (error) {
    failures++;
    console.log(`  FAIL  ${name}\n        ${error.message}`);
  }
}

const stores = await api('/stores');
const store = stores[0];
const products = await api('/products');
const bySku = (sku) => products.find((p) => p.sku === sku);
const rice = bySku('ABA-ARR-1K');
const water = bySku('BEB-AGU-1L');
const order = (paymentMethod, lines) => api('/orders', { body: { storeId: store.id, paymentMethod, lines } });
const orderStatus = (id) => api(`/orders/${id}`).then((o) => o.status);
const stockOf = async (id) => (await api(`/products/${id}`)).stock;
const untilStatus = (id, status) => waitFor(`order ${id} to be ${status}`, async () => (await orderStatus(id)) === status);

console.log(`Ordering as ${store.name}`);

await step('a card order goes from placed to delivered, surviving a declined card', async () => {
  const { id } = await order('CARD', [
    { productId: rice.id, quantity: 3 },
    { productId: water.id, quantity: 5 },
  ]);
  await untilStatus(id, 'AWAITING_PAYMENT');
  const placed = await api(`/orders/${id}`);
  assert(placed.totalCents === 3 * rice.priceCents + 5 * water.priceCents, 'total does not match catalogue prices');

  await waitFor('payment to open', () => api(`/payments/order/${id}`));
  const card = (number) => ({ number, expMonth: 12, expYear: 2030, cvc: '123', holderName: 'Smoke Test' });
  const declined = await api(`/payments/order/${id}/card`, { body: card('4000000000000002') });
  assert(declined.result === 'declined', 'expected a decline');
  const approved = await api(`/payments/order/${id}/card`, { body: card('4242424242424242') });
  assert(approved.result === 'approved', 'expected approval');
  await untilStatus(id, 'CONFIRMED');

  const shipment = await waitFor('shipment', async () => (await api('/shipments')).find((s) => s.orderId === id));
  for (const status of ['PREPARING', 'IN_TRANSIT', 'DELIVERED']) {
    await api(`/shipments/${shipment.id}/advance`, { method: 'POST' });
    await untilStatus(id, status);
  }
  const timeline = (await api(`/orders/${id}`)).timeline.map((s) => s.step);
  for (const expected of ['PLACED', 'STOCK_RESERVED', 'PAYMENT_FAILED', 'PAID', 'PREPARING', 'IN_TRANSIT', 'DELIVERED']) {
    assert(timeline.includes(expected), `timeline is missing ${expected}: ${timeline.join(', ')}`);
  }

  await waitFor('three emails to the store', async () => {
    const response = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${store.email} subject:${id.slice(0, 8).toUpperCase()}`)}`);
    const result = await response.json();
    return result.messages.length >= 3;
  });
});

await step('a SPEI order gets a valid CLABE and is confirmed by the deposit', async () => {
  const { id } = await order('SPEI', [{ productId: water.id, quantity: 2 }]);
  const payment = await waitFor('SPEI payment', () => api(`/payments/order/${id}`));
  assert(clabeValid(payment.clabe), `invalid CLABE ${payment.clabe}`);
  assert(/^\d{7}$/.test(payment.reference), 'reference should be 7 digits');
  try {
    await api('/payments/spei/deposits', { body: { reference: payment.reference, amountCents: payment.amountCents - 1 } });
    throw new Error('a short deposit was accepted');
  } catch (error) {
    assert(error.status === 422, error.message);
  }
  await api('/payments/spei/deposits', { body: { reference: payment.reference, amountCents: payment.amountCents } });
  await untilStatus(id, 'CONFIRMED');
});

await step('an order for more than the warehouse has is rejected and takes nothing', async () => {
  const before = await stockOf(rice.id);
  const scarce = bySku('BOT-CHI-60');
  const { id } = await order('CARD', [
    { productId: rice.id, quantity: 1 },
    { productId: scarce.id, quantity: 500 },
  ]);
  await untilStatus(id, 'REJECTED');
  assert((await api(`/orders/${id}`)).failureReason.includes('Chicharrón'), 'reason should name the product');
  assert((await stockOf(rice.id)) === before, 'rice stock changed for a rejected order');
});

await step('cancelling before paying puts the stock back', async () => {
  const before = await stockOf(water.id);
  const { id } = await order('CARD', [{ productId: water.id, quantity: 4 }]);
  await untilStatus(id, 'AWAITING_PAYMENT');
  assert((await stockOf(water.id)) === before - 4, 'stock was not reserved');
  await api(`/orders/${id}/cancel`, { body: { storeId: store.id } });
  await untilStatus(id, 'CANCELLED');
  await waitFor('stock to return', async () => (await stockOf(water.id)) === before);
  const payment = await waitFor('payment to be cancelled', async () => {
    const p = await api(`/payments/order/${id}`);
    return p.status === 'CANCELLED' ? p : null;
  });
  assert(payment, 'payment should be cancelled');
});

await step('the gateway does not route unknown paths', async () => {
  try {
    await api('/internal/anything');
    throw new Error('expected 404');
  } catch (error) {
    assert(error.status === 404, error.message);
  }
});

console.log(failures === 0 ? '\nAll smoke checks passed.' : `\n${failures} smoke check(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
