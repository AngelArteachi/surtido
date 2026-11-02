import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { BoxLabel } from '../components/BoxLabel.tsx';
import { Empty, ErrorNote, Loading, Stepper } from '../components/ui.tsx';
import { api, type Category, type Product } from '../lib/api.ts';
import { useShop } from '../lib/cart.tsx';
import { money } from '../lib/format.ts';

function ProductCard({ product }: { product: Product }) {
  const { lines, setQuantity } = useShop();
  const inCart = lines.find((l) => l.product.id === product.id)?.quantity ?? 0;
  const soldOut = product.stock === 0;
  return (
    <article className="flex flex-col overflow-hidden rounded-md border border-line bg-surface">
      <BoxLabel product={product} />
      <div className="flex flex-1 flex-col gap-2 p-3">
        <h3 className="leading-snug font-semibold">{product.name}</h3>
        <p className="text-sm text-muted">{product.pack}</p>
        <div className="mt-auto flex items-end justify-between gap-2">
          <div>
            <div className="num text-lg font-semibold">{money(product.priceCents)}</div>
            <div className={`num text-xs ${soldOut ? 'text-bad' : product.lowStock ? 'text-warn' : 'text-muted'}`}>
              {soldOut ? 'Agotado' : product.lowStock ? `Últimas ${product.stock}` : `${product.stock} en almacén`}
            </div>
          </div>
          {inCart === 0 ? (
            <button type="button" className="btn btn-quiet py-1.5 text-sm" disabled={soldOut} onClick={() => setQuantity(product, 1)}>
              Agregar
            </button>
          ) : (
            <Stepper value={inCart} max={Math.min(500, product.stock)} onChange={(n) => setQuantity(product, n)} label={`Cantidad de ${product.name}`} />
          )}
        </div>
      </div>
    </article>
  );
}

function Cart() {
  const { storeId, lines, setQuantity, clear, totalCents, packs } = useShop();
  const [method, setMethod] = useState<'CARD' | 'SPEI'>('CARD');
  const navigate = useNavigate();

  const place = useMutation({
    mutationFn: () =>
      api<{ id: string }>('/orders', {
        body: { storeId, paymentMethod: method, lines: lines.map((l) => ({ productId: l.product.id, quantity: l.quantity })) },
      }),
    onSuccess: ({ id }) => {
      clear();
      navigate(`/pedidos/${id}`);
    },
  });

  return (
    <aside id="carrito" className="flex flex-col gap-4 rounded-md border border-line bg-surface p-4 lg:sticky lg:top-24">
      <div className="flex items-baseline justify-between">
        <h2 className="font-display text-2xl font-extrabold uppercase">Tu pedido</h2>
        <span className="num text-sm text-muted">
          {packs} {packs === 1 ? 'bulto' : 'bultos'}
        </span>
      </div>
      {lines.length === 0 ? (
        <p className="text-sm text-muted">Agrega productos del catálogo. Te los llevamos a tu tienda.</p>
      ) : (
        <>
          <ul className="flex flex-col divide-y divide-line">
            {lines.map(({ product, quantity }) => (
              <li key={product.id} className="flex items-center gap-3 py-2 text-sm">
                <div className="min-w-0 flex-1">
                  <div className="truncate font-medium">{product.name}</div>
                  <div className="num text-muted">
                    {quantity} × {money(product.priceCents)}
                  </div>
                </div>
                <Stepper value={quantity} max={Math.min(500, product.stock)} onChange={(n) => setQuantity(product, n)} label={`Cantidad de ${product.name}`} />
              </li>
            ))}
          </ul>
          <div className="num flex items-baseline justify-between border-t-2 border-ink pt-3">
            <span className="font-semibold">Total estimado</span>
            <span className="text-xl font-semibold">{money(totalCents)}</span>
          </div>
          <fieldset className="grid grid-cols-2 gap-2">
            <legend className="label mb-2">Forma de pago</legend>
            {(
              [
                ['CARD', 'Tarjeta', 'Se cobra al confirmar'],
                ['SPEI', 'Transferencia', 'SPEI con CLABE'],
              ] as const
            ).map(([value, title, note]) => (
              <label key={value} className={`cursor-pointer rounded-md border-2 px-3 py-2 ${method === value ? 'border-ink' : 'border-line'}`}>
                <input type="radio" name="method" id={`method-${value}`} className="sr-only" checked={method === value} onChange={() => setMethod(value)} />
                <span className="block font-semibold">{title}</span>
                <span className="block text-xs text-muted">{note}</span>
              </label>
            ))}
          </fieldset>
          <ErrorNote error={place.error} />
          <button type="button" className="btn btn-primary py-3 text-lg" disabled={!storeId || place.isPending} onClick={() => place.mutate()}>
            {place.isPending ? 'Enviando…' : 'Hacer pedido'}
          </button>
          <p className="text-xs text-muted">El almacén confirma precios y existencias al apartar tu mercancía. Tienes 15 minutos para pagar.</p>
        </>
      )}
    </aside>
  );
}

export function Catalog() {
  const [params, setParams] = useSearchParams();
  const category = params.get('categoria') ?? '';
  const [q, setQ] = useState('');

  const categories = useQuery({ queryKey: ['categories'], queryFn: () => api<Category[]>('/categories') });
  const products = useQuery({
    queryKey: ['products', category, q],
    queryFn: () => api<Product[]>(`/products?${new URLSearchParams({ ...(category && { category }), ...(q && { q }) })}`),
    refetchInterval: 10_000,
  });

  const chip = (active: boolean) =>
    `shrink-0 rounded-md border px-3 py-1.5 text-sm font-semibold ${active ? 'border-ink bg-ink text-paper' : 'border-line bg-surface hover:border-ink'}`;

  return (
    <div className="page grid gap-8 pt-8 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <div className="min-w-0">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="label">Distribuidora · Villahermosa, Tab.</p>
            <h1 className="font-display text-5xl leading-none font-extrabold uppercase">Surte tu tienda</h1>
            <p className="mt-2 max-w-xl text-muted">Pide por caja o por bulto antes de las 2 p.m. y te entregamos hoy mismo en tu tienda.</p>
          </div>
          <label htmlFor="search" className="w-full sm:w-64">
            <span className="sr-only">Buscar producto</span>
            <input id="search" className="field" placeholder="Buscar producto o SKU" value={q} onChange={(e) => setQ(e.target.value)} />
          </label>
        </div>

        <div className="mt-6 flex gap-2 overflow-x-auto pb-1" role="group" aria-label="Categorías">
          <button type="button" className={chip(!category)} onClick={() => setParams({}, { replace: true })}>
            Todo
          </button>
          {categories.data?.map((c) => (
            <button key={c.name} type="button" className={chip(category === c.name)} onClick={() => setParams({ categoria: c.name }, { replace: true })}>
              {c.name} <span className="num opacity-60">{c.products}</span>
            </button>
          ))}
        </div>

        <div className="mt-6">
          {products.isPending && <Loading label="Cargando el catálogo" />}
          <ErrorNote error={products.error} />
          {products.data?.length === 0 && <Empty title="Sin resultados">Prueba con otra palabra o categoría.</Empty>}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
            {products.data?.map((p) => <ProductCard key={p.id} product={p} />)}
          </div>
        </div>
      </div>
      <Cart />
    </div>
  );
}
