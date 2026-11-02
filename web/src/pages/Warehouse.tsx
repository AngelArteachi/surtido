import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Empty, ErrorNote, Loading, Stamp } from '../components/ui.tsx';
import { api, type Notification, type OrderStatus, type Payment, type Product, type Shipment } from '../lib/api.ts';
import { dateTime, money, shortId, time } from '../lib/format.ts';

const columns: Array<{ status: Shipment['status']; title: string; action: string | null }> = [
  { status: 'PENDING', title: 'Por surtir', action: 'Empezar a preparar' },
  { status: 'PREPARING', title: 'Preparando', action: 'Cargar a la ruta' },
  { status: 'IN_TRANSIT', title: 'En ruta', action: 'Marcar entregado' },
  { status: 'DELIVERED', title: 'Entregados hoy', action: null },
];

function Shipments() {
  const queryClient = useQueryClient();
  const shipments = useQuery({ queryKey: ['shipments'], queryFn: () => api<Shipment[]>('/shipments'), refetchInterval: 2_000 });
  const advance = useMutation({
    mutationFn: (id: string) => api(`/shipments/${id}/advance`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['shipments'] }),
  });

  if (shipments.isPending) return <Loading />;
  return (
    <>
      <ErrorNote error={shipments.error ?? advance.error} />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {columns.map((column) => {
          const items = shipments.data?.filter((s) => s.status === column.status) ?? [];
          return (
            <section key={column.status} className="flex flex-col gap-3 rounded-md bg-sunken p-3" aria-label={column.title}>
              <h3 className="flex items-center justify-between font-display text-lg font-extrabold uppercase">
                {column.title} <span className="num rounded bg-surface px-2 text-sm">{items.length}</span>
              </h3>
              {items.length === 0 && <p className="px-1 text-sm text-muted">Nada por aquí.</p>}
              {items.map((s) => (
                <article key={s.id} className="flex flex-col gap-2 rounded-md border border-line bg-surface p-3 text-sm">
                  <div className="flex items-baseline justify-between gap-2">
                    <Link to={`/pedidos/${s.orderId}`} className="font-mono font-medium hover:underline">
                      {shortId(s.orderId)}
                    </Link>
                    <span className="num text-muted">{time(s.updatedAt)}</span>
                  </div>
                  <div>
                    <div className="font-semibold">{s.storeName}</div>
                    <div className="text-muted">
                      {s.address}, {s.city}
                    </div>
                  </div>
                  <div className="num flex justify-between text-muted">
                    <span>
                      {s.packs} {s.packs === 1 ? 'bulto' : 'bultos'}
                    </span>
                    <span>{money(s.totalCents)}</span>
                  </div>
                  {s.driver && <div className="text-xs">Chofer: {s.driver}</div>}
                  {column.action && (
                    <button type="button" className="btn btn-dark py-1.5 text-sm" disabled={advance.isPending} onClick={() => advance.mutate(s.id)}>
                      {column.action}
                    </button>
                  )}
                </article>
              ))}
            </section>
          );
        })}
      </div>
    </>
  );
}

function Inventory() {
  const queryClient = useQueryClient();
  const [onlyLow, setOnlyLow] = useState(false);
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const products = useQuery({
    queryKey: ['inventory', onlyLow],
    queryFn: () => api<Product[]>(`/products${onlyLow ? '?lowStock=true' : ''}`),
    refetchInterval: 3_000,
  });
  const restock = useMutation({
    mutationFn: ({ id, quantity }: { id: string; quantity: number }) => api(`/products/${id}/restock`, { body: { quantity } }),
    onSuccess: (_d, { id }) => {
      setAmounts((a) => ({ ...a, [id]: '' }));
      queryClient.invalidateQueries({ queryKey: ['inventory'] });
    },
  });

  return (
    <>
      <label className="mb-3 flex items-center gap-2 text-sm" htmlFor="only-low">
        <input id="only-low" type="checkbox" checked={onlyLow} onChange={(e) => setOnlyLow(e.target.checked)} /> Solo productos por agotarse
      </label>
      <ErrorNote error={products.error ?? restock.error} />
      <div className="overflow-x-auto rounded-md border border-line bg-surface">
        <table className="num w-full min-w-[40rem] text-sm">
          <thead className="bg-sunken text-left">
            <tr>
              <th className="label px-3 py-2">SKU</th>
              <th className="label px-3 py-2">Producto</th>
              <th className="label px-3 py-2 text-right">Precio</th>
              <th className="label px-3 py-2 text-right">Existencias</th>
              <th className="label px-3 py-2">Entrada de mercancía</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {products.data?.map((p) => (
              <tr key={p.id} className={p.lowStock ? 'bg-warn-soft' : ''}>
                <td className="px-3 py-2 font-mono text-xs">{p.sku}</td>
                <td className="px-3 py-2">
                  {p.name} <span className="text-muted">· {p.pack}</span>
                </td>
                <td className="px-3 py-2 text-right">{money(p.priceCents)}</td>
                <td className={`px-3 py-2 text-right font-semibold ${p.stock === 0 ? 'text-bad' : p.lowStock ? 'text-warn' : ''}`}>{p.stock}</td>
                <td className="px-3 py-2">
                  <form
                    className="flex gap-2"
                    onSubmit={(e) => {
                      e.preventDefault();
                      const quantity = Number(amounts[p.id]);
                      if (quantity > 0) restock.mutate({ id: p.id, quantity });
                    }}
                  >
                    <label htmlFor={`restock-${p.id}`} className="sr-only">
                      Unidades recibidas de {p.name}
                    </label>
                    <input
                      id={`restock-${p.id}`}
                      type="number"
                      min={1}
                      className="field w-24 py-1"
                      value={amounts[p.id] ?? ''}
                      onChange={(e) => setAmounts({ ...amounts, [p.id]: e.target.value })}
                    />
                    <button type="submit" className="btn btn-quiet py-1 text-xs">
                      Recibir
                    </button>
                  </form>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function Transfers() {
  const queryClient = useQueryClient();
  const pending = useQuery({ queryKey: ['spei'], queryFn: () => api<Payment[]>('/payments/spei/pending'), refetchInterval: 3_000 });
  const deposit = useMutation({
    mutationFn: (p: Payment) => api('/payments/spei/deposits', { body: { reference: p.reference, amountCents: p.amountCents } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['spei'] }),
  });
  if (pending.isPending) return <Loading />;
  return (
    <>
      <p className="mb-3 max-w-2xl text-sm text-muted">
        Transferencias que las tiendas deben hacer. En producción el banco avisa cada depósito; aquí lo registras a mano para simularlo.
      </p>
      <ErrorNote error={pending.error ?? deposit.error} />
      {pending.data?.length === 0 && <Empty title="Sin transferencias pendientes" />}
      <ul className="flex flex-col divide-y divide-line rounded-md border border-line bg-surface">
        {pending.data?.map((p) => (
          <li key={p.id} className="grid items-center gap-x-4 gap-y-1 px-4 py-3 text-sm sm:grid-cols-[7rem_1fr_9rem_auto]">
            <Link to={`/pedidos/${p.orderId}`} className="font-mono font-medium hover:underline">
              {shortId(p.orderId)}
            </Link>
            <span className="font-mono text-muted">
              Ref. {p.reference} · vence {time(p.expiresAt)}
            </span>
            <span className="num font-semibold">{money(p.amountCents)}</span>
            <button type="button" className="btn btn-dark py-1.5 text-xs" disabled={deposit.isPending} onClick={() => deposit.mutate(p)}>
              Registrar depósito
            </button>
          </li>
        ))}
      </ul>
    </>
  );
}

function Emails() {
  const emails = useQuery({ queryKey: ['notifications'], queryFn: () => api<Notification[]>('/notifications'), refetchInterval: 3_000 });
  if (emails.isPending) return <Loading />;
  return (
    <>
      <p className="mb-3 max-w-2xl text-sm text-muted">
        Correos a las tiendas. Si el servidor de correo falla, cada correo se reintenta por Kafka tres veces antes de marcarse como fallido.
      </p>
      <ErrorNote error={emails.error} />
      {emails.data?.length === 0 && <Empty title="Aún no se envían correos" />}
      <ul className="flex flex-col divide-y divide-line rounded-md border border-line bg-surface">
        {emails.data?.map((n) => (
          <li key={n.id} className="grid items-center gap-x-4 gap-y-1 px-4 py-3 text-sm sm:grid-cols-[8rem_1fr_auto]">
            <span className="num text-muted">{dateTime(n.createdAt)}</span>
            <span className="min-w-0">
              <span className="font-medium">{n.subject}</span> <span className="text-muted">→ {n.recipient || 'sin destinatario'}</span>
              {n.lastError && <span className="block text-xs text-bad">{n.lastError}</span>}
            </span>
            <span className="flex items-center gap-2">
              {n.attempts > 1 && <span className="text-xs text-muted">{n.attempts} intentos</span>}
              <Stamp tone={n.status === 'SENT' ? 'ok' : 'bad'}>{n.status === 'SENT' ? 'Enviado' : 'Fallido'}</Stamp>
            </span>
          </li>
        ))}
      </ul>
    </>
  );
}

const tabs = [
  ['envios', 'Envíos'],
  ['inventario', 'Inventario'],
  ['transferencias', 'Transferencias SPEI'],
  ['correos', 'Correos'],
] as const;

export function Warehouse() {
  const [params, setParams] = useSearchParams();
  const current = params.get('vista') ?? 'envios';
  const stats = useQuery({
    queryKey: ['stats'],
    queryFn: () => api<Array<{ status: OrderStatus; orders: number; totalCents: number }>>('/orders/stats'),
    refetchInterval: 3_000,
  });
  const count = (...statuses: OrderStatus[]) => stats.data?.filter((s) => statuses.includes(s.status)).reduce((n, s) => n + s.orders, 0) ?? 0;
  const sold = stats.data?.filter((s) => ['CONFIRMED', 'PREPARING', 'IN_TRANSIT', 'DELIVERED'].includes(s.status)).reduce((n, s) => n + s.totalCents, 0) ?? 0;

  return (
    <div className="page pt-8">
      <p className="label">Panel del almacén</p>
      <h1 className="font-display text-5xl font-extrabold uppercase">Almacén</h1>

      <dl className="mt-6 grid grid-cols-2 gap-px overflow-hidden rounded-md bg-line sm:grid-cols-4">
        {[
          ['Vendido hoy', money(sold)],
          ['Esperando pago', String(count('AWAITING_PAYMENT', 'PENDING_STOCK'))],
          ['Por entregar', String(count('CONFIRMED', 'PREPARING', 'IN_TRANSIT'))],
          ['Sin existencias o vencidos', String(count('REJECTED', 'EXPIRED'))],
        ].map(([label, value]) => (
          <div key={label} className="bg-surface p-4">
            <dt className="label">{label}</dt>
            <dd className="num font-display text-3xl font-extrabold">{value}</dd>
          </div>
        ))}
      </dl>

      <div className="mt-6 flex gap-1 overflow-x-auto border-b border-line" role="tablist">
        {tabs.map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={current === key}
            className={`shrink-0 border-b-4 px-4 py-2 font-display text-lg font-semibold uppercase ${current === key ? 'border-yellow text-ink' : 'border-transparent text-muted hover:text-ink'}`}
            onClick={() => setParams({ vista: key }, { replace: true })}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="mt-5">
        {current === 'envios' && <Shipments />}
        {current === 'inventario' && <Inventory />}
        {current === 'transferencias' && <Transfers />}
        {current === 'correos' && <Emails />}
      </div>
    </div>
  );
}
