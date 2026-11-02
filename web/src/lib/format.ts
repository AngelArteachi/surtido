import type { OrderStatus } from './api.ts';

const pesos = new Intl.NumberFormat('es-MX', { style: 'currency', currency: 'MXN' });
export const money = (cents: number) => pesos.format(cents / 100);

export const shortId = (id: string) => id.slice(0, 8).toUpperCase();

export const time = (iso: string) =>
  new Intl.DateTimeFormat('es-MX', { hour: 'numeric', minute: '2-digit', second: '2-digit' }).format(new Date(iso));

export const dateTime = (iso: string) =>
  new Intl.DateTimeFormat('es-MX', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }).format(new Date(iso));

export function clock(seconds: number) {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export const statusLabel: Record<OrderStatus, string> = {
  PENDING_STOCK: 'Revisando existencias',
  AWAITING_PAYMENT: 'Esperando pago',
  CONFIRMED: 'Pagado',
  PREPARING: 'En preparación',
  IN_TRANSIT: 'En ruta',
  DELIVERED: 'Entregado',
  REJECTED: 'Sin existencias',
  EXPIRED: 'Vencido',
  CANCELLED: 'Cancelado',
};

export type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'muted';

export const statusTone: Record<OrderStatus, Tone> = {
  PENDING_STOCK: 'info',
  AWAITING_PAYMENT: 'warn',
  CONFIRMED: 'ok',
  PREPARING: 'ok',
  IN_TRANSIT: 'ok',
  DELIVERED: 'ok',
  REJECTED: 'bad',
  EXPIRED: 'bad',
  CANCELLED: 'muted',
};

/** Each category gets a box-label colour, as hue for oklch(). */
export const categoryHue: Record<string, number> = {
  Abarrotes: 70,
  Bebidas: 240,
  Botanas: 35,
  Lácteos: 210,
  Limpieza: 175,
  Panadería: 55,
};
