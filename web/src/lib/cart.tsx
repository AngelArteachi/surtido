import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { Product } from './api.ts';

export interface CartLine {
  product: Product;
  quantity: number;
}

interface ShopState {
  storeId: string | null;
  setStoreId: (id: string) => void;
  lines: CartLine[];
  setQuantity: (product: Product, quantity: number) => void;
  clear: () => void;
  totalCents: number;
  packs: number;
}

const ShopContext = createContext<ShopState | null>(null);

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

function write(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Without storage the cart lasts until the tab closes.
  }
}

/** Which store is buying, and its cart. Kept in the browser between visits. */
export function ShopProvider({ children }: { children: ReactNode }) {
  const [storeId, setStoreId] = useState<string | null>(() => read('surtido.store', null));
  const [lines, setLines] = useState<CartLine[]>(() => read('surtido.cart', []));

  useEffect(() => write('surtido.store', storeId), [storeId]);
  useEffect(() => write('surtido.cart', lines), [lines]);

  const value = useMemo<ShopState>(
    () => ({
      storeId,
      setStoreId,
      lines,
      setQuantity: (product, quantity) =>
        setLines((current) => {
          const others = current.filter((l) => l.product.id !== product.id);
          if (quantity <= 0) return others;
          const index = current.findIndex((l) => l.product.id === product.id);
          const next = { product, quantity };
          return index === -1 ? [...current, next] : current.map((l) => (l.product.id === product.id ? next : l));
        }),
      clear: () => setLines([]),
      totalCents: lines.reduce((sum, l) => sum + l.product.priceCents * l.quantity, 0),
      packs: lines.reduce((sum, l) => sum + l.quantity, 0),
    }),
    [storeId, lines],
  );

  return <ShopContext.Provider value={value}>{children}</ShopContext.Provider>;
}

export function useShop(): ShopState {
  const value = useContext(ShopContext);
  if (!value) throw new Error('useShop outside ShopProvider');
  return value;
}
