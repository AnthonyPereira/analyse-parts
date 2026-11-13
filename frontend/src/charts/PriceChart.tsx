import { useState } from 'react'
import { Area, AreaChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useIntraday, usePrices } from '../api/queries'
import type { PriceRange } from '../api/types'
import { formatPrice } from '../format'
import { ChartCard, EmptyChart, TooltipBox } from './common'
import { AXIS, GRID } from './theme'

const RANGES: { id: PriceRange; label: string }[] = [
  { id: 'D1', label: '1 j' }, { id: 'M1', label: '1 m' }, { id: 'M6', label: '6 m' },
  { id: 'Y1', label: '1 an' }, { id: 'Y5', label: '5 ans' }, { id: 'MAX', label: 'Max' },
]

interface Point { t: number; price: number }

/**
 * L'évolution du cours. « 1 j » montre la séance du jour (un point toutes les 5 minutes,
 * puis chaque nouveau cours reçu en direct) avec la clôture de la veille en repère.
 */
export function PriceChart({ isin, currency }: { isin: string; currency: string }) {
  const [range, setRange] = useState<PriceRange>('D1')
  const intraday = useIntraday(isin, range === 'D1')
  const daily = usePrices(isin, range === 'D1' ? 'Y1' : range, range !== 'D1')

  const points: Point[] = range === 'D1'
    ? (intraday.data?.points ?? []).map(p => ({ t: Date.parse(p.time), price: p.price }))
    : (daily.data ?? []).map(c => ({ t: Date.parse(c.day), price: c.close }))
  const reference = range === 'D1' ? intraday.data?.previousClose ?? null : points[0]?.price ?? null
  const last = points[points.length - 1]?.price
  const change = reference && last ? last / reference - 1 : null
  const pending = range === 'D1' ? intraday.isPending : daily.isPending

  const timeFormat = (t: number) => range === 'D1'
    ? new Date(t).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
    : new Date(t).toLocaleDateString('fr-FR', range === 'M1' || range === 'M6'
      ? { day: '2-digit', month: 'short' } : { month: 'short', year: '2-digit' })

  return (
    <ChartCard
      title="Cours de bourse" help="PRICE_CHART"
      subtitle={change !== null && (
        <>
          <span className={change >= 0 ? 'delta-up' : 'delta-down'}>
            {change >= 0 ? '▲' : '▼'} {(change * 100).toLocaleString('fr-FR', { maximumFractionDigits: 2 })} %
          </span>{' '}
          {range === 'D1' ? 'depuis la clôture d\'hier' : 'sur la période'}
        </>
      )}
      actions={
        <div className="range-tabs" role="group" aria-label="Période">
          {RANGES.map(r => (
            <button key={r.id} aria-pressed={r.id === range} onClick={() => setRange(r.id)}>{r.label}</button>
          ))}
        </div>
      }>
      {points.length < 2 ? (
        <EmptyChart>{pending ? 'Chargement…' : range === 'D1' ? 'Pas encore de séance aujourd\'hui.' : 'Pas d\'historique.'}</EmptyChart>
      ) : (
        <ResponsiveContainer width="100%" height={260}>
          <AreaChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid {...GRID} />
            <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={timeFormat}
                   minTickGap={40} {...AXIS} />
            <YAxis domain={['auto', 'auto']} width={56} {...AXIS} axisLine={false}
                   tickFormatter={v => v.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} />
            {reference !== null && range === 'D1' && (
              <ReferenceLine y={reference} stroke="var(--axis)" strokeWidth={1}
                             label={{ value: 'clôture veille', position: 'insideTopLeft', fill: 'var(--axis)', fontSize: 11 }} />
            )}
            <Tooltip
              cursor={{ stroke: 'var(--axis)', strokeWidth: 1 }}
              content={({ active, payload }) => active && payload?.length ? (
                <TooltipBox
                  title={range === 'D1' ? new Date(payload[0].payload.t).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' })
                    : new Date(payload[0].payload.t).toLocaleDateString('fr-FR')}
                  rows={[{ label: 'Cours', value: formatPrice(payload[0].payload.price, currency) }]} />
              ) : null} />
            <Area type="linear" dataKey="price" stroke="var(--series-1)" strokeWidth={2} fill="var(--series-1)"
                  fillOpacity={0.1} isAnimationActive={false} dot={false}
                  activeDot={{ r: 4, stroke: 'var(--surface)', strokeWidth: 2 }} />
          </AreaChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  )
}
