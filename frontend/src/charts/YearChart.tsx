import { Bar, BarChart, CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { ReactNode } from 'react'
import { glossary } from '../glossary'
import { ChartCard, EmptyChart, Legend, TooltipBox } from './common'
import { AXIS, GRID, SERIES } from './theme'

export interface Series {
  key: string
  label: string
}

export type YearRow = { year: string } & Record<string, number | string | null>

/**
 * Une ou plusieurs séries par exercice : colonnes pour des montants, lignes pour des taux.
 * Un seul axe vertical : toutes les séries d'un graphique ont la même unité (sinon, deux graphiques).
 */
export function YearChart({ title, subtitle, kind, series, data, format, tickFormat, zeroLine, help }: {
  title: string
  subtitle?: ReactNode
  help?: string
  kind: 'column' | 'line'
  series: Series[]
  data: YearRow[]
  format: (v: number) => string
  tickFormat?: (v: number) => string
  zeroLine?: boolean
}) {
  // On ne garde que les séries qui ont au moins une valeur.
  const visible = series.filter(s => data.some(d => typeof d[s.key] === 'number'))
  const color = (s: Series) => SERIES[series.indexOf(s)] // la couleur suit la série, pas son rang d'affichage
  const hasData = visible.length > 0 && data.length > 0

  const tooltip = (
    <Tooltip
      cursor={kind === 'column' ? { fill: 'var(--grid)', fillOpacity: 0.4 } : { stroke: 'var(--axis)', strokeWidth: 1 }}
      content={({ active, payload, label }) => active && payload?.length ? (
        <TooltipBox title={`Exercice ${label}`} rows={visible.map(s => {
          const v = payload[0].payload[s.key]
          return { label: s.label, value: typeof v === 'number' ? format(v) : '—', color: color(s) }
        })} />
      ) : null} />
  )
  const axes = (
    <>
      <CartesianGrid {...GRID} />
      <XAxis dataKey="year" {...AXIS} />
      <YAxis width={52} {...AXIS} axisLine={false} tickFormatter={tickFormat ?? format} />
      {zeroLine && <ReferenceLine y={0} stroke="var(--baseline)" />}
    </>
  )

  return (
    // Une seule série : pas de légende, c'est le titre qui porte la définition.
    <ChartCard title={title} subtitle={subtitle} help={help ?? (series.length === 1 && glossary(series[0].key) ? series[0].key : undefined)}>
      <Legend items={visible.map(s => ({ label: s.label, color: color(s), kind: kind === 'line' ? 'line' : 'box',
        term: glossary(s.key) ? s.key : undefined }))} />
      {!hasData ? <EmptyChart>Données indisponibles</EmptyChart> : (
        <ResponsiveContainer width="100%" height={210}>
          {kind === 'column' ? (
            <BarChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: 0 }} barGap={2} barCategoryGap="22%">
              {axes}
              {tooltip}
              {visible.map(s => (
                <Bar key={s.key} dataKey={s.key} name={s.label} fill={color(s)} maxBarSize={24}
                     radius={[4, 4, 0, 0]} isAnimationActive={false} />
              ))}
            </BarChart>
          ) : (
            <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
              {axes}
              {tooltip}
              {visible.map(s => (
                <Line key={s.key} dataKey={s.key} name={s.label} stroke={color(s)} strokeWidth={2} type="linear"
                      connectNulls dot={{ r: 4, fill: color(s), stroke: 'var(--surface)', strokeWidth: 2 }}
                      activeDot={{ r: 5, stroke: 'var(--surface)', strokeWidth: 2 }} isAnimationActive={false} />
              ))}
            </LineChart>
          )}
        </ResponsiveContainer>
      )}
    </ChartCard>
  )
}
