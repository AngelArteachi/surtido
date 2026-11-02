export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** Spring returns RFC 9457 problem details; `detail` carries the reason. */
export async function api<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method: options.method ?? (options.body === undefined ? 'GET' : 'POST'),
      headers: options.body === undefined ? undefined : { 'content-type': 'application/json' },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError(0, 'No pudimos conectar con Surtido. Revisa tu conexión.');
  }
  if (response.status === 204 || response.status === 202 && response.headers.get('content-length') === '0') return undefined as T;
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok) throw new ApiError(response.status, translate(body?.detail ?? response.statusText, response.status));
  return body as T;
}

/** The services answer in English; the stores read Spanish. */
function translate(detail: string, status: number): string {
  const known: Record<string, string> = {
    'This order can no longer be cancelled': 'Este pedido ya no se puede cancelar.',
    'Card number is not valid': 'El número de tarjeta no es válido.',
    'The card has expired': 'La tarjeta está vencida.',
    'No payment for this order yet': 'El pago todavía se está preparando. Espera un momento.',
    'The deposit does not match the amount due': 'El depósito no coincide con el monto del pedido.',
    'No payment with that reference': 'No hay ningún pago con esa referencia.',
    'Already delivered': 'Este envío ya se entregó.',
  };
  if (known[detail]) return known[detail]!;
  if (detail.startsWith('This payment is already')) return 'Este pago ya se procesó.';
  if (status === 503 || status === 502) return 'Un servicio no responde. Intenta de nuevo en un momento.';
  return detail || 'Algo salió mal.';
}

// --- Shapes -----------------------------------------------------------------

export interface Product {
  id: string;
  sku: string;
  name: string;
  category: string;
  pack: string;
  priceCents: number;
  stock: number;
  lowStock: boolean;
}

export interface Category {
  name: string;
  products: number;
}

export interface Store {
  id: string;
  name: string;
  ownerName: string;
  email: string;
  address: string;
  city: string;
}

export type OrderStatus =
  | 'PENDING_STOCK'
  | 'AWAITING_PAYMENT'
  | 'CONFIRMED'
  | 'PREPARING'
  | 'IN_TRANSIT'
  | 'DELIVERED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELLED';

export interface Order {
  id: string;
  status: OrderStatus;
  paymentMethod: 'CARD' | 'SPEI';
  totalCents: number | null;
  failureReason: string | null;
  createdAt: string;
  expiresAt: string | null;
  store: Store;
  lines: Array<{ productId: string; productName: string | null; quantity: number; unitPriceCents: number | null }>;
  timeline: Array<{ step: string; detail: string; occurredAt: string }>;
}

export interface OrderSummary {
  id: string;
  storeName: string;
  status: OrderStatus;
  paymentMethod: 'CARD' | 'SPEI';
  totalCents: number | null;
  lines: number;
  createdAt: string;
}

export interface Payment {
  id: string;
  orderId: string;
  amountCents: number;
  method: 'CARD' | 'SPEI';
  status: 'REQUIRES_PAYMENT' | 'SUCCEEDED' | 'CANCELLED' | 'REFUNDED';
  clabe: string | null;
  reference: string | null;
  cardBrand: string | null;
  cardLast4: string | null;
  failedAttempts: number;
  expiresAt: string;
}

export interface Shipment {
  id: string;
  orderId: string;
  storeName: string;
  address: string;
  city: string;
  packs: number;
  totalCents: number;
  status: 'PENDING' | 'PREPARING' | 'IN_TRANSIT' | 'DELIVERED';
  driver: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Notification {
  id: number;
  orderId: string;
  recipient: string;
  subject: string;
  status: 'SENT' | 'FAILED';
  attempts: number;
  lastError: string | null;
  createdAt: string;
}
