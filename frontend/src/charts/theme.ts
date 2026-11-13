// Couleurs, axes et graduations partagés par les graphiques (hors composants : rechargement à chaud de Vite).

/** Couleurs des séries, dans un ordre fixe (jamais recyclé) : la 1re série est toujours bleue. */
export const SERIES = ['var(--series-1)', 'var(--series-2)', 'var(--series-3)', 'var(--series-4)',
  'var(--series-5)', 'var(--series-6)', 'var(--series-7)']

/** Style des axes : discrets, la donnée doit ressortir, pas le décor. */
export const AXIS = {
  tick: { fill: 'var(--axis)', fontSize: 11 },
  axisLine: { stroke: 'var(--baseline)' },
  tickLine: false,
} as const

export const GRID = { stroke: 'var(--grid)', vertical: false } as const

/** Graduations compactes pour un axe de montants : 80 Md, 500 M… */
export function compactAmount(v: number): string {
  const a = Math.abs(v)
  if (a >= 1e9) return `${(v / 1e9).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} Md`
  if (a >= 1e6) return `${(v / 1e6).toLocaleString('fr-FR', { maximumFractionDigits: 0 })} M`
  if (a >= 1e3) return `${(v / 1e3).toLocaleString('fr-FR', { maximumFractionDigits: 0 })} k`
  return v.toLocaleString('fr-FR', { maximumFractionDigits: 2 })
}
