import { useNavigate } from 'react-router'
import {
  Bar, BarChart, CartesianGrid, ReferenceArea, ReferenceLine, ResponsiveContainer, Scatter, ScatterChart, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { ProfileDefinition, ProfileId, UniverseEntry } from '../api/types'
import { AXIS, ChartCard, EmptyChart, GRID, Legend, TooltipBox } from './common'

/** Combien de sociétés par tranche de 10 points : le profil est-il sévère ou généreux ? */
export function ScoreHistogram({ scores, title }: { scores: number[]; title: string }) {
  const bins = Array.from({ length: 10 }, (_, i) => ({
    range: i === 9 ? '90–100' : `${i * 10}–${i * 10 + 9}`,
    count: scores.filter(s => (i === 9 ? s >= 90 : s >= i * 10 && s < i * 10 + 10)).length,
  }))
  return (
    <ChartCard title={title} help="HISTOGRAM" subtitle={`${scores.length} sociétés notées, par tranche de 10 points. Plus une barre est haute, plus il y a de sociétés dans cette tranche.`}>
      {scores.length === 0 ? <EmptyChart>Aucune société notée</EmptyChart> : (
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={bins} margin={{ top: 16, right: 4, bottom: 0, left: 0 }} barCategoryGap={2}>
            <CartesianGrid {...GRID} />
            <XAxis dataKey="range" {...AXIS} interval={0} fontSize={10} />
            <YAxis width={30} allowDecimals={false} {...AXIS} axisLine={false} />
            <Tooltip cursor={{ fill: 'var(--grid)', fillOpacity: 0.4 }}
                     content={({ active, payload }) => active && payload?.length ? (
                       <TooltipBox title={`Score ${payload[0].payload.range}`}
                                   rows={[{ label: 'Sociétés', value: String(payload[0].payload.count) }]} />
                     ) : null} />
            <Bar dataKey="count" fill="var(--series-1)" radius={[4, 4, 0, 0]} maxBarSize={40} isAnimationActive={false}
                 label={{ position: 'top', fill: 'var(--muted)', fontSize: 11, formatter: (v: unknown) => (v ? String(v) : '') }} />
          </BarChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  )
}

/**
 * Deux profils face à face, une société par point. Avec Qualité (vertical) et Value
 * (horizontal), le coin en haut à droite rassemble les belles entreprises à prix raisonnable.
 * Un clic ouvre la fiche.
 */
export function ProfileScatter({ universe, x, y, profiles, highlightSector }: {
  universe: UniverseEntry[]
  x: ProfileId
  y: ProfileId
  profiles: ProfileDefinition[] | undefined
  highlightSector?: string
}) {
  const navigate = useNavigate()
  const label = (id: ProfileId) => profiles?.find(p => p.id === id)?.label ?? id
  const points = universe
    .filter(u => u.scores[x] !== null && u.scores[y] !== null)
    .map(u => ({ x: u.scores[x]!, y: u.scores[y]!, name: u.name, isin: u.isin, sector: u.supersector }))
  const inSector = highlightSector ? points.filter(p => p.sector === highlightSector) : points
  const others = highlightSector ? points.filter(p => p.sector !== highlightSector) : []

  const tooltip = (
    <Tooltip cursor={false} content={({ active, payload }) => active && payload?.length ? (
      <TooltipBox title={payload[0].payload.name} rows={[
        { label: label(y), value: String(payload[0].payload.y) },
        { label: label(x), value: String(payload[0].payload.x) },
        { label: 'Secteur', value: payload[0].payload.sector ?? '—' },
      ]} />
    ) : null} />
  )
  const open = (p: unknown) => {
    const isin = (p as { payload?: { isin?: string } })?.payload?.isin
    if (isin) navigate(`/societe/${isin}`)
  }

  return (
    <ChartCard title={`${label(y)} et ${label(x)}`} help="SCATTER"
               subtitle={`Chaque point est une société notée dans les deux profils (${points.length}). En haut à droite : bonne note dans les deux profils. Cliquer pour ouvrir la fiche.`}>
      {highlightSector && <Legend items={[
        { label: highlightSector, color: 'var(--series-1)' }, { label: 'Autres secteurs', color: 'var(--dot-muted)' }]} />}
      {points.length === 0 ? <EmptyChart>Pas de données</EmptyChart> : (
        <ResponsiveContainer width="100%" height={340}>
          <ScatterChart margin={{ top: 8, right: 12, bottom: 18, left: 0 }}>
            <CartesianGrid {...GRID} vertical />
            <ReferenceArea x1={60} x2={100} y1={60} y2={100} fill="var(--good)" fillOpacity={0.06}
                           label={{ value: 'les deux ≥ 60', position: 'insideTopRight', fill: 'var(--axis)', fontSize: 11 }} />
            <ReferenceLine x={50} stroke="var(--baseline)" />
            <ReferenceLine y={50} stroke="var(--baseline)" />
            <XAxis type="number" dataKey="x" domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} {...AXIS}
                   label={{ value: `Score ${label(x)}`, position: 'insideBottom', offset: -10, fill: 'var(--muted)', fontSize: 12 }} />
            <YAxis type="number" dataKey="y" domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} width={36} {...AXIS}
                   label={{ value: label(y), angle: -90, position: 'insideLeft', fill: 'var(--muted)', fontSize: 12 }} />
            {tooltip}
            {others.length > 0 && (
              <Scatter data={others} fill="var(--dot-muted)" isAnimationActive={false} onClick={open} cursor="pointer" />
            )}
            <Scatter data={inSector} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={2}
                     isAnimationActive={false} onClick={open} cursor="pointer" />
          </ScatterChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  )
}
