import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useScoreHistory } from '../api/queries'
import type { ProfileDefinition, ProfileId, ScoreOrigin } from '../api/types'
import { Term } from '../components/Term'
import { formatDate } from '../format'
import { AXIS, ChartCard, EmptyChart, GRID, Legend, SERIES, TooltipBox } from './common'

/** L'ordre des profils fixe leur couleur : Qualité est toujours la 1re couleur, même si un profil manque. */
const ORDER: ProfileId[] = ['QUALITY', 'GROWTH', 'DIVIDEND', 'VALUE']

export const ORIGIN_LABEL: Record<ScoreOrigin, string> = {
  FILING: 'Nouveau rapport',
  DAILY: 'Photo du soir',
  BACKFILL: 'Clôture d\'exercice',
}

type Row = { t: number; origin: ScoreOrigin; day: string } & Partial<Record<ProfileId, number>>

/**
 * L'historique des 4 notes. Axe du temps réel (pas une catégorie par point) : les notes
 * reconstituées sont espacées d'un an, les photos du soir d'un jour, et l'écart se voit.
 * La courbe du profil choisi est mise en avant, les autres restent en retrait.
 */
export function ScoreHistoryChart({ isin, profiles, current }: {
  isin: string
  profiles: ProfileDefinition[] | undefined
  current: ProfileId
}) {
  const { data, isPending } = useScoreHistory(isin)
  const rows: Row[] = (data ?? []).map(p => ({ t: Date.parse(p.day), origin: p.origin, day: p.day, ...p.scores }))
  const label = (id: ProfileId) => profiles?.find(p => p.id === id)?.label ?? id
  const color = (id: ProfileId) => SERIES[ORDER.indexOf(id)]
  const visible = ORDER.filter(id => rows.some(r => typeof r[id] === 'number'))
  const filings = rows.filter(r => r.origin === 'FILING')
  const yearsOnly = rows.length > 0 && rows[rows.length - 1].t - rows[0].t > 400 * 24 * 3600 * 1000

  return (
    <ChartCard title="Historique des notes" help="SCORE_HISTORY"
      subtitle={<>Les 4 notes dans le temps. Les gros points sont des <Term id="SCORE_ORIGIN">clôtures d'exercice</Term>,
        les pointillés l'arrivée d'un rapport.</>}>
      <Legend items={visible.map(id => ({ label: label(id), color: color(id), kind: 'line', term: id }))} />
      {rows.length < 2 ? (
        <EmptyChart>{isPending ? 'Chargement…' : 'Pas encore assez de notes enregistrées.'}</EmptyChart>
      ) : (
        <ResponsiveContainer width="100%" height={240}>
          <LineChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
            <CartesianGrid {...GRID} />
            <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} {...AXIS}
                   tickFormatter={t => new Date(t).toLocaleDateString('fr-FR',
                     yearsOnly ? { year: 'numeric' } : { day: '2-digit', month: 'short' })} />
            <YAxis domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} width={36} {...AXIS} axisLine={false} />
            {filings.map(f => (
              <ReferenceLine key={f.t} x={f.t} stroke="var(--axis)" strokeDasharray="3 3" />
            ))}
            <Tooltip cursor={{ stroke: 'var(--axis)', strokeWidth: 1 }}
              content={({ active, payload }) => {
                if (!active || !payload?.length) return null
                const r = payload[0].payload as Row
                return (
                  <TooltipBox title={`${formatDate(r.day)} · ${ORIGIN_LABEL[r.origin]}`}
                    rows={visible.map(id => ({
                      label: label(id),
                      value: typeof r[id] === 'number' ? `${r[id]} / 100` : '—',
                      color: color(id),
                    }))} />
                )
              }} />
            {visible.map(id => {
              const main = id === current
              return (
                <Line key={id} dataKey={id} name={label(id)} stroke={color(id)} type="linear" connectNulls
                      strokeWidth={main ? 2.5 : 1.5} strokeOpacity={main ? 1 : 0.45} isAnimationActive={false}
                      // un point visible seulement aux clôtures d'exercice et aux rapports, pas chaque soir
                      dot={(props: { cx?: number; cy?: number; index?: number; payload?: Row }) => {
                        const { cx, cy, index, payload } = props
                        if (cx === undefined || cy === undefined || !payload || payload.origin === 'DAILY'
                          || typeof payload[id] !== 'number') {
                          return <g key={`${id}-${index}`} />
                        }
                        return <circle key={`${id}-${index}`} cx={cx} cy={cy} r={main ? 4 : 3} fill={color(id)}
                                       fillOpacity={main ? 1 : 0.6} stroke="var(--surface)" strokeWidth={2} />
                      }}
                      activeDot={{ r: 5, stroke: 'var(--surface)', strokeWidth: 2 }} />
              )
            })}
          </LineChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  )
}
