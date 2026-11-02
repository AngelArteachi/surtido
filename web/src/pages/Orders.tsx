import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Empty, ErrorNote, Loading, Stamp } from '../components/ui.tsx';
import { api, type OrderSummary } from '../lib/api.ts';
import { useShop } from '../lib/cart.tsx';
import { dateTime, money, shortId, statusLabel, statusTone } from '../lib/format.ts';

export function Orders() {
  const { storeId } = useShop();
  const orders = useQuery({
    queryKey: ['orders', storeId],
    queryFn: () => api<OrderSummary[]>(`/orders?storeId=${storeId}`),
    enabled: Boolean(storeId),
    refetchInterval: 5_000,
  });

  return (
    <div className="page pt-8">
      <h1 className="font-display text-5xl font-extrabold uppercase">Mis pedidos</h1>
      <div className="mt-6">
        {orders.isPending && <Loading />}
        <ErrorNote error={orders.error} />
        {orders.data?.length === 0 && (
          <Empty title="Todavía no has pedido">
            <Link to="/" className="font-semibold underline">
              Ve al catálogo
            </Link>{' '}
            para hacer tu primer pedido.
          </Empty>
        )}
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line bg-surface">
          {orders.data?.map((order) => (
            <li key={order.id}>
              <Link to={`/pedidos/${order.id}`} className="grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1 px-4 py-3 hover:bg-sunken sm:grid-cols-[8rem_1fr_10rem_8rem]">
                <span className="font-mono font-medium">{shortId(order.id)}</span>
                <span className="text-sm text-muted sm:order-none">
                  {dateTime(order.createdAt)} · {order.lines} {order.lines === 1 ? 'producto' : 'productos'} · {order.paymentMethod === 'SPEI' ? 'SPEI' : 'Tarjeta'}
                </span>
                <span>
                  <Stamp tone={statusTone[order.status]}>{statusLabel[order.status]}</Stamp>
                </span>
                <span className="num text-right font-semibold">{order.totalCents !== null ? money(order.totalCents) : '—'}</span>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
