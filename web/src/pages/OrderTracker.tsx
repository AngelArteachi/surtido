import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { ErrorNote, Loading, Stamp, useSecondsUntil } from '../components/ui.tsx';
import { api, type Order, type OrderStatus, type Payment } from '../lib/api.ts';
import { useShop } from '../lib/cart.tsx';
import { clock, money, shortId, statusLabel, statusTone, time } from '../lib/format.ts';

type StepState = 'done' | 'current' | 'failed' | 'pending';

/** The saga as the store sees it: each stop names the service that handles it. */
function sagaSteps(status: OrderStatus): Array<{ title: string; service: string; state: StepState }> {
  const order: OrderStatus[] = ['PENDING_STOCK', 'AWAITING_PAYMENT', 'CONFIRMED', 'PREPARING', 'IN_TRANSIT', 'DELIVERED'];
  const reached = order.indexOf(status);
  // For each stop: the status from which it is done, and from which it is in progress.
  const doneFrom = [0, 1, 2, 4, 5, 5];
  const currentFrom = [-1, 0, 1, 2, 4, 5];
  const at = (i: number): StepState => {
    if (status === 'REJECTED') return i === 0 ? 'done' : i === 1 ? 'failed' : 'pending';
    if (status === 'EXPIRED' || status === 'CANCELLED') return i <= 1 ? 'done' : i === 2 ? 'failed' : 'pending';
    return reached >= doneFrom[i]! ? 'done' : reached >= currentFrom[i]! ? 'current' : 'pending';
  };
  return [
    { title: 'Pedido', service: 'orders', state: 'done' },
    { title: 'Existencias', service: 'catalog', state: at(1) },
    { title: 'Pago', service: 'payments', state: at(2) },
    { title: 'Preparación', service: 'shipping', state: at(3) },
    { title: 'En ruta', service: 'shipping', state: at(4) },
    { title: 'Entregado', service: 'shipping', state: at(5) },
  ];
}

function SagaTrack({ status }: { status: OrderStatus }) {
  const steps = sagaSteps(status);
  const style: Record<StepState, string> = {
    done: 'bg-ink text-paper border-ink',
    current: 'bg-yellow text-yellow-ink border-ink animate-pulse',
    failed: 'bg-bad text-white border-bad',
    pending: 'bg-surface text-muted border-line',
  };
  return (
    <ol className="grid grid-cols-3 gap-y-5 sm:grid-cols-6" aria-label="Avance del pedido">
      {steps.map((step, i) => (
        <li key={step.title} className="relative flex flex-col items-center text-center">
          {i > 0 && (
            <span
              className={`absolute top-4 right-1/2 hidden h-0.5 w-full sm:block ${step.state === 'pending' ? 'bg-line' : 'bg-ink'}`}
              aria-hidden="true"
            />
          )}
          <span className={`relative z-10 grid size-8 place-items-center rounded-full border-2 font-display font-extrabold ${style[step.state]}`}>
            {step.state === 'failed' ? '×' : step.state === 'done' ? '✓' : i + 1}
          </span>
          <span className="mt-2 text-sm font-semibold">{step.title}</span>
          <span className="font-mono text-[0.68rem] text-muted">{step.service}</span>
        </li>
      ))}
    </ol>
  );
}

const TEST_CARDS = [
  ['Aprobada', '4242 4242 4242 4242'],
  ['Rechazada', '4000 0000 0000 0002'],
  ['Sin fondos', '4000 0000 0000 9995'],
] as const;

function CardPayment({ orderId, onDone }: { orderId: string; onDone: () => void }) {
  const [card, setCard] = useState({ number: '', expiry: '12/30', cvc: '123', holderName: '' });
  const pay = useMutation({
    mutationFn: () => {
      const [month, year] = card.expiry.split('/').map((p) => Number(p.trim()));
      return api<{ result: 'approved' | 'declined'; reason: string | null }>(`/payments/order/${orderId}/card`, {
        body: { number: card.number, expMonth: month, expYear: year, cvc: card.cvc, holderName: card.holderName },
      });
    },
    onSuccess: onDone,
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    pay.mutate();
  };
  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-2 text-xs">
        <span className="self-center text-muted">Tarjetas de prueba:</span>
        {TEST_CARDS.map(([label, number]) => (
          <button key={number} type="button" className="btn btn-quiet px-2 py-1 text-xs" onClick={() => setCard({ ...card, number, holderName: card.holderName || 'María Hernández' })}>
            {label} <span className="font-mono text-muted">{number.slice(-4)}</span>
          </button>
        ))}
      </div>
      <label className="flex flex-col gap-1 text-sm font-medium" htmlFor="card-number">
        Número de tarjeta
        <input id="card-number" className="field font-mono" inputMode="numeric" autoComplete="cc-number" required value={card.number} onChange={(e) => setCard({ ...card, number: e.target.value })} />
      </label>
      <div className="grid grid-cols-[1fr_6rem_5rem] gap-2">
        <label className="flex flex-col gap-1 text-sm font-medium" htmlFor="card-name">
          Titular
          <input id="card-name" className="field" autoComplete="cc-name" required value={card.holderName} onChange={(e) => setCard({ ...card, holderName: e.target.value })} />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium" htmlFor="card-exp">
          Vence
          <input id="card-exp" className="field font-mono" placeholder="MM/AA" required value={card.expiry} onChange={(e) => setCard({ ...card, expiry: e.target.value })} />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium" htmlFor="card-cvc">
          CVC
          <input id="card-cvc" className="field font-mono" inputMode="numeric" required maxLength={4} value={card.cvc} onChange={(e) => setCard({ ...card, cvc: e.target.value })} />
        </label>
      </div>
      {pay.data?.result === 'declined' && (
        <p role="alert" className="rounded-md bg-bad-soft px-3 py-2 text-sm font-medium text-bad">
          Tarjeta rechazada: {pay.data.reason}. Tu mercancía sigue apartada; prueba con otra tarjeta.
        </p>
      )}
      <ErrorNote error={pay.error} />
      <button type="submit" className="btn btn-primary py-2.5" disabled={pay.isPending}>
        {pay.isPending ? 'Cobrando…' : 'Pagar con tarjeta'}
      </button>
    </form>
  );
}

function SpeiPayment({ payment, onDone }: { payment: Payment; onDone: () => void }) {
  const deposit = useMutation({
    mutationFn: () => api('/payments/spei/deposits', { body: { reference: payment.reference, amountCents: payment.amountCents } }),
    onSuccess: onDone,
  });
  const clabe = payment.clabe ?? '';
  const grouped = `${clabe.slice(0, 3)} ${clabe.slice(3, 6)} ${clabe.slice(6, 17)} ${clabe.slice(17)}`;
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-muted">Transfiere desde tu banca en línea a esta cuenta. Usa la referencia para que identifiquemos tu pago.</p>
      <dl className="grid gap-3 rounded-md bg-sunken p-4 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <dt className="label">CLABE</dt>
          <dd className="num font-mono text-xl font-medium tracking-wider select-all">{grouped}</dd>
        </div>
        <div>
          <dt className="label">Referencia</dt>
          <dd className="font-mono text-lg font-medium select-all">{payment.reference}</dd>
        </div>
        <div>
          <dt className="label">Monto exacto</dt>
          <dd className="num text-lg font-semibold">{money(payment.amountCents)}</dd>
        </div>
      </dl>
      <ErrorNote error={deposit.error} />
      <button type="button" className="btn btn-quiet" disabled={deposit.isPending} onClick={() => deposit.mutate()}>
        Simular que llegó la transferencia (demo)
      </button>
    </div>
  );
}

export function OrderTracker() {
  const { id = '' } = useParams();
  const { storeId } = useShop();
  const queryClient = useQueryClient();

  const order = useQuery({
    queryKey: ['order', id],
    queryFn: () => api<Order>(`/orders/${id}`),
    refetchInterval: (q) => (['DELIVERED', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(q.state.data?.status ?? '') ? 10_000 : 1_000),
  });
  const awaiting = order.data?.status === 'AWAITING_PAYMENT';
  const payment = useQuery({
    queryKey: ['payment', id],
    queryFn: () => api<Payment>(`/payments/order/${id}`),
    enabled: awaiting,
    retry: true,
    retryDelay: 700,
  });
  const secondsLeft = useSecondsUntil(order.data?.expiresAt);

  const cancel = useMutation({
    mutationFn: () => api(`/orders/${id}/cancel`, { body: { storeId: order.data?.store.id ?? storeId } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['order', id] }),
  });

  if (order.isPending) return <div className="page"><Loading /></div>;
  if (order.error) return <div className="page pt-8"><ErrorNote error={order.error} /></div>;
  const o = order.data;
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['order', id] });
    queryClient.invalidateQueries({ queryKey: ['payment', id] });
  };

  return (
    <div className="page grid gap-8 pt-8 lg:grid-cols-[minmax(0,1fr)_24rem]">
      <div className="flex min-w-0 flex-col gap-6">
        <div>
          <Link to="/pedidos" className="text-sm text-muted hover:text-ink">
            ← Mis pedidos
          </Link>
          <div className="mt-2 flex flex-wrap items-center gap-3">
            <h1 className="font-display text-4xl font-extrabold uppercase">Pedido {shortId(o.id)}</h1>
            <Stamp tone={statusTone[o.status]}>{statusLabel[o.status]}</Stamp>
          </div>
          <p className="text-muted">
            {o.store.name} · {o.store.address}, {o.store.city}
          </p>
        </div>

        <section className="rounded-md border border-line bg-surface p-5">
          <SagaTrack status={o.status} />
          {o.failureReason && <p className="mt-5 rounded-md bg-bad-soft px-4 py-3 text-sm font-medium text-bad">{o.failureReason}</p>}
        </section>

        {awaiting && (
          <section className="rounded-md border-2 border-ink bg-surface p-5" aria-label="Pago">
            <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="font-display text-2xl font-extrabold uppercase">
                Paga {o.totalCents !== null && money(o.totalCents)}
              </h2>
              <span className={`num text-sm font-semibold ${secondsLeft < 120 ? 'text-bad' : 'text-muted'}`}>
                Tu mercancía está apartada {clock(secondsLeft)} más
              </span>
            </div>
            {!payment.data ? (
              <Loading label="Preparando el pago" />
            ) : o.paymentMethod === 'CARD' ? (
              <CardPayment orderId={o.id} onDone={refresh} />
            ) : (
              <SpeiPayment payment={payment.data} onDone={refresh} />
            )}
          </section>
        )}

        <section aria-label="Productos">
          <h2 className="label mb-2">Productos</h2>
          <table className="num w-full text-sm">
            <tbody className="divide-y divide-line">
              {o.lines.map((line) => (
                <tr key={line.productId}>
                  <td className="py-2 pr-3 whitespace-nowrap">{line.quantity} ×</td>
                  <td className="w-full py-2">{line.productName ?? <span className="text-muted">Confirmando con el almacén…</span>}</td>
                  <td className="py-2 text-right whitespace-nowrap">{line.unitPriceCents !== null ? money(line.unitPriceCents * line.quantity) : '—'}</td>
                </tr>
              ))}
            </tbody>
            {o.totalCents !== null && (
              <tfoot>
                <tr className="border-t-2 border-ink font-semibold">
                  <td className="py-2" colSpan={2}>
                    Total
                  </td>
                  <td className="py-2 text-right">{money(o.totalCents)}</td>
                </tr>
              </tfoot>
            )}
          </table>
        </section>

        {(o.status === 'PENDING_STOCK' || awaiting) && (
          <div>
            <ErrorNote error={cancel.error} />
            <button type="button" className="btn btn-quiet mt-2 text-sm" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
              Cancelar pedido
            </button>
          </div>
        )}
      </div>

      <aside className="self-start rounded-md bg-ink p-5 text-paper lg:sticky lg:top-24" aria-label="Bitácora">
        <h2 className="font-display text-xl font-extrabold tracking-wide uppercase">Bitácora</h2>
        <p className="mb-4 text-xs opacity-70">Cada paso que dio tu pedido entre los servicios, en vivo.</p>
        <ol className="flex flex-col gap-3">
          {o.timeline.map((step, i) => (
            <li key={i} className="grid grid-cols-[4rem_1fr] gap-2 text-sm">
              <span className="num font-mono text-xs opacity-60">{time(step.occurredAt)}</span>
              <div>
                <span className="font-mono text-[0.68rem] text-yellow">{step.step}</span>
                <p className="leading-snug">{step.detail}</p>
              </div>
            </li>
          ))}
        </ol>
      </aside>
    </div>
  );
}
