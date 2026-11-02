import { useEffect, useState, type ReactNode } from 'react';
import type { Tone } from '../lib/format.ts';

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <p role="alert" className="rounded-md bg-bad-soft px-4 py-3 text-sm font-medium text-bad">
      {error instanceof Error ? error.message : 'Algo salió mal.'}
    </p>
  );
}

export function Loading({ label = 'Cargando' }: { label?: string }) {
  return (
    <p className="flex items-center gap-3 py-12 text-muted" aria-live="polite">
      <span className="size-2.5 animate-pulse bg-yellow" /> {label}…
    </p>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="rounded-md border-2 border-dashed border-line px-6 py-12 text-center">
      <p className="font-display text-xl font-semibold uppercase">{title}</p>
      {children && <div className="mx-auto mt-1 max-w-md text-muted">{children}</div>}
    </div>
  );
}

const tones: Record<Tone, string> = {
  ok: 'border-ok text-ok',
  warn: 'border-warn text-warn',
  bad: 'border-bad text-bad',
  info: 'border-info text-info',
  muted: 'border-muted text-muted',
};

/** A rubber-stamp status mark, as on a delivery note. */
export function Stamp({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className={`inline-flex -rotate-2 items-center rounded-sm border-2 px-2 py-0.5 font-display text-xs font-extrabold tracking-[0.08em] uppercase ${tones[tone]}`}>
      {children}
    </span>
  );
}

export function useSecondsUntil(iso: string | null | undefined): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return iso ? Math.max(0, Math.round((new Date(iso).getTime() - now) / 1000)) : 0;
}

export function Stepper({ value, max, onChange, label }: { value: number; max: number; onChange: (n: number) => void; label: string }) {
  return (
    <div className="flex items-center rounded-md border border-line bg-surface" role="group" aria-label={label}>
      <button type="button" className="px-3 py-1.5 font-semibold disabled:opacity-40" aria-label="Quitar uno" disabled={value <= 0} onClick={() => onChange(value - 1)}>
        −
      </button>
      <span className="num w-8 text-center font-semibold" aria-live="polite">
        {value}
      </span>
      <button type="button" className="px-3 py-1.5 font-semibold disabled:opacity-40" aria-label="Agregar uno" disabled={value >= max} onClick={() => onChange(value + 1)}>
        +
      </button>
    </div>
  );
}
