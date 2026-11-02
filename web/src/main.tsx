import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { Layout } from './components/Layout.tsx';
import { Empty } from './components/ui.tsx';
import { ApiError } from './lib/api.ts';
import { ShopProvider } from './lib/cart.tsx';
import { Catalog } from './pages/Catalog.tsx';
import { Orders } from './pages/Orders.tsx';
import { OrderTracker } from './pages/OrderTracker.tsx';
import { Warehouse } from './pages/Warehouse.tsx';
import './index.css';

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { path: '/', element: <Catalog /> },
      { path: '/pedidos', element: <Orders /> },
      { path: '/pedidos/:id', element: <OrderTracker /> },
      { path: '/almacen', element: <Warehouse /> },
      {
        path: '*',
        element: (
          <div className="page pt-12">
            <Empty title="Esta página no existe">Revisa el enlace o vuelve al catálogo.</Empty>
          </div>
        ),
      },
    ],
  },
]);

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1_000,
      retry: (failures, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failures < 2,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ShopProvider>
        <RouterProvider router={router} />
      </ShopProvider>
    </QueryClientProvider>
  </StrictMode>,
);
