import type { Product } from '../lib/api.ts';
import { categoryHue } from '../lib/format.ts';

/**
 * Products are drawn as the label printed on their shipping box: a category
 * band, the pack size in big condensed type, and the SKU. No stock photos.
 */
export function BoxLabel({ product }: { product: Product }) {
  const hue = categoryHue[product.category] ?? 140;
  const [unit, ...rest] = product.pack.split(' ');
  return (
    <div
      className="relative flex aspect-[5/4] flex-col justify-between overflow-hidden rounded-t-md p-3 text-[oklch(0.22_0.04_var(--h))]"
      style={{
        ['--h' as string]: hue,
        background: `linear-gradient(135deg, oklch(0.86 0.09 ${hue}), oklch(0.78 0.12 ${hue}))`,
      }}
      aria-hidden="true"
    >
      {/* Corrugated cardboard edge */}
      <div className="absolute inset-x-0 top-0 h-2 bg-[repeating-linear-gradient(90deg,rgb(0_0_0/0.14)_0_2px,transparent_2px_6px)]" />
      <div className="flex items-start justify-between gap-2 pt-1">
        <span className="font-display text-xs font-extrabold tracking-[0.14em] uppercase">{product.category}</span>
        <span className="font-mono text-[0.65rem] opacity-75">{product.sku}</span>
      </div>
      <div className="leading-[0.85]">
        <div className="font-display text-sm font-semibold uppercase opacity-80">{unit}</div>
        <div className="font-display text-4xl font-extrabold uppercase">{rest.join(' ')}</div>
      </div>
      {/* "This side up" arrows */}
      <svg viewBox="0 0 24 24" className="absolute right-3 bottom-3 size-6 opacity-50">
        <path d="M7 20V6M4 9l3-3 3 3M17 20V6M14 9l3-3 3 3" stroke="currentColor" strokeWidth="2" fill="none" />
      </svg>
    </div>
  );
}
