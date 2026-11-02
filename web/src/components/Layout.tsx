import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import { api, type Store } from '../lib/api.ts';
import { useShop } from '../lib/cart.tsx';

const tab = ({ isActive }: { isActive: boolean }) =>
  `px-3 py-1.5 text-sm font-semibold rounded-md transition-colors ${isActive ? 'bg-ink text-paper' : 'text-muted hover:text-ink'}`;

export function Layout() {
  const { storeId, setStoreId, packs } = useShop();
  const location = useLocation();
  const inWarehouse = location.pathname.startsWith('/almacen');
  const stores = useQuery({ queryKey: ['stores'], queryFn: () => api<Store[]>('/stores'), staleTime: 60_000 });

  // First visit: buy as the first demo store.
  useEffect(() => {
    if (!storeId && stores.data?.[0]) setStoreId(stores.data[0].id);
  }, [storeId, stores.data, setStoreId]);

  return (
    <div className="flex min-h-screen flex-col">
      {inWarehouse && <div className="hazard h-1.5" aria-hidden="true" />}
      <header className="sticky top-[env(safe-area-inset-top,0px)] z-20 border-b border-line bg-paper/95 backdrop-blur">
        <div className="page flex min-h-16 flex-wrap items-center gap-x-5 gap-y-2 py-2">
          <Link to="/" className="flex items-center gap-2" aria-label="Surtido, inicio">
            <svg viewBox="0 0 32 32" className="size-8" aria-hidden="true">
              <rect width="32" height="32" rx="6" fill="var(--ink)" />
              <path d="M8 12l8-4 8 4v9l-8 4-8-4z" fill="var(--yellow)" />
              <path d="M8 12l8 4 8-4M16 16v9" stroke="var(--ink)" strokeWidth="1.6" fill="none" />
            </svg>
            <span className="font-display text-2xl font-extrabold tracking-wide uppercase">Surtido</span>
          </Link>

          <nav className="flex gap-1" aria-label="Área">
            <NavLink to="/" end className={tab}>
              Catálogo
            </NavLink>
            <NavLink to="/pedidos" className={tab}>
              Mis pedidos
            </NavLink>
            <NavLink to="/almacen" className={tab}>
              Almacén
            </NavLink>
          </nav>

          <div className="ml-auto flex items-center gap-3">
            {!inWarehouse && (
              <label className="flex items-center gap-2 text-sm" htmlFor="store">
                <span className="label hidden sm:inline">Comprando como</span>
                <select id="store" className="field max-w-[14rem] py-1.5" value={storeId ?? ''} onChange={(e) => setStoreId(e.target.value)}>
                  {stores.data?.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {!inWarehouse && packs > 0 && (
              <Link to="/#carrito" className="btn btn-dark py-1.5 text-sm">
                Carrito <span className="num rounded bg-yellow px-1.5 text-yellow-ink">{packs}</span>
              </Link>
            )}
          </div>
        </div>
      </header>
      <main className="flex-1 pb-16">
        <Outlet />
      </main>
      <footer className="border-t border-line py-6 text-sm text-muted">
        <div className="page flex flex-wrap justify-between gap-3">
          <span>Surtido es un proyecto de demostración. Los pagos son simulados.</span>
          <span className="flex flex-wrap gap-x-4">
            <a className="hover:text-ink" href="http://localhost:8092" target="_blank" rel="noreferrer">Kafka UI</a>
            <a className="hover:text-ink" href="http://localhost:16687" target="_blank" rel="noreferrer">Trazas</a>
            <a className="hover:text-ink" href="http://localhost:3001" target="_blank" rel="noreferrer">Grafana</a>
            <a className="hover:text-ink" href="http://localhost:8761" target="_blank" rel="noreferrer">Eureka</a>
            <a className="hover:text-ink" href="http://localhost:8026" target="_blank" rel="noreferrer">Correos</a>
          </span>
        </div>
      </footer>
    </div>
  );
}
