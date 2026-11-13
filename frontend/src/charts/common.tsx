// Briques communes à tous les graphiques : cadre, légende, infobulle, couleurs, axes.
import type { ReactNode } from 'react'
import { Term } from '../components/Term'

/**
 * Le cadre d'un graphique. {@code help} : identifiant du lexique qui explique comment lire le
 * graphique (ou ce qu'il mesure) ; le titre devient alors un mot avec sa bulle d'aide.
 */
export function ChartCard({ title, subtitle, children, actions, help }: {
  title: string
  subtitle?: ReactNode
  children: ReactNode
  actions?: ReactNode
  help?: string
}) {
  return (
    <figure className="chart-card" style={{ margin: 0 }}>
      <div className="chart-head">
        <h3>{help ? <Term id={help}>{title}</Term> : title}</h3>
        {actions}
      </div>
      {subtitle && <p className="subtitle">{subtitle}</p>}
      {children}
    </figure>
  )
}

export interface LegendItem {
  label: string
  color: string
  kind?: 'box' | 'line'
  /** Identifiant du lexique : le libellé devient un mot avec sa définition. */
  term?: string
}

/** Légende en HTML, au-dessus du graphique : obligatoire dès 2 séries (la couleur seule ne suffit pas). */
export function Legend({ items }: { items: LegendItem[] }) {
  if (items.length < 2) return null
  return (
    <ul className="legend">
      {items.map(i => (
        <li key={i.label}>
          <span className={i.kind === 'line' ? 'swatch-line' : 'swatch'} style={{ background: i.color }} />
          {i.term ? <Term id={i.term}>{i.label}</Term> : i.label}
        </li>
      ))}
    </ul>
  )
}

/** Contenu d'infobulle : un titre (la date) et une ligne par série. */
export function TooltipBox({ title, rows }: {
  title: ReactNode
  rows: { label: string; value: string; color?: string }[]
}) {
  return (
    <div className="tooltip">
      <p className="tooltip-title">{title}</p>
      <ul>
        {rows.map(r => (
          <li key={r.label}>
            {r.color && <span className="swatch" style={{ background: r.color }} />}
            <span>{r.label}</span>
            <span className="tooltip-value">{r.value}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function EmptyChart({ children }: { children: ReactNode }) {
  return <div className="chart-empty">{children}</div>
}
