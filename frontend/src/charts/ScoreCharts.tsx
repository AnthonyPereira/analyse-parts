import { Link } from 'react-router'
import { CartesianGrid, Line, LineChart, ReferenceDot, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { ProfileDefinition, ProfileId, RatioDefinition, SectorAdjustment, UniverseEntry } from '../api/types'
import { formatRatio, scoreTone } from '../format'
import { Term } from '../components/Term'
import { Legend, TooltipBox } from './common'
import { AXIS, GRID, SERIES } from './theme'
import { useWidth } from './useWidth'

const TONE_COLOR = { good: 'var(--good)', mid: 'var(--mid)', bad: 'var(--bad)', none: 'var(--muted)' }

/** Barres horizontales 0–100 (notes par famille, par profil…), valeur au bout. */
export function ScoreBars({ items }: { items: { label: string; score: number | null; to?: string }[] }) {
  return (
    <ul className="hbars">
      {items.map(i => (
        <li key={i.label}>
          <span>{i.to ? <Link to={i.to}>{i.label}</Link> : i.label}</span>
          <span className="hbar" role="img" aria-label={`${i.label} : ${i.score ?? 'non noté'} sur 100`}>
            <span style={{ width: `${i.score ?? 0}%`, background: TONE_COLOR[scoreTone(i.score)] }} />
          </span>
          <span className="num">{i.score ?? '—'}</span>
        </li>
      ))}
    </ul>
  )
}

/** Les notes d'une société dans les 4 profils : une même société peut être « Qualité » sans être « Value ». */
export function ProfileScores({ entry, profiles, current }: {
  entry: UniverseEntry | undefined
  profiles: ProfileDefinition[] | undefined
  current: ProfileId
}) {
  if (!entry || !profiles) return null
  return (
    <ScoreBars items={profiles.map(p => ({
      label: p.id === current ? `${p.label} ←` : p.label,
      score: entry.scores[p.id],
      to: `?profil=${p.id}`,
    }))} />
  )
}

/**
 * Le barème d'un indicateur : comment une valeur devient des points. La courbe générale, la
 * courbe adaptée au secteur s'il y en a une, et le point de la société.
 */
export function ScaleChart({ def, value, points, adjustment }: {
  def: RatioDefinition
  value: number
  points: number
  adjustment: SectorAdjustment | null
}) {
  const general = def.scale
  const adjusted = adjustment ? general.map(a => ({ value: a.value * adjustment.factor, points: a.points })) : null
  const xs = [...general, ...(adjusted ?? [])].map(a => a.value).concat(value)
  const min = Math.min(...xs)
  const max = Math.max(...xs)
  const pad = (max - min) * 0.08 || 1
  const lo = min - pad
  const hi = max + pad
  // On prolonge chaque barème à plat au-delà de ses repères extrêmes, comme le calcul.
  const extend = (s: { value: number; points: number }[]) =>
    [{ value: lo, points: s[0].points }, ...s, { value: hi, points: s[s.length - 1].points }]
  const unit = def.unit
  const series = [
    { key: 'general', label: 'Barème général', data: extend(general) },
    ...(adjusted ? [{ key: 'adjusted', label: `Barème du secteur ${adjustment!.label}`, data: extend(adjusted) }] : []),
  ]
  return (
    <div className="scale-chart">
      <p className="small muted"><Term id="SCALE_CHART">Comment lire cette courbe</Term> : la valeur de l'indicateur en bas, les points obtenus à gauche.</p>
      <Legend items={series.map((s, i) => ({ label: s.label, color: SERIES[i], kind: 'line' }))} />
      <ResponsiveContainer width="100%" height={150}>
        <LineChart margin={{ top: 10, right: 12, bottom: 0, left: 0 }}>
          <CartesianGrid {...GRID} />
          <XAxis dataKey="value" type="number" domain={[lo, hi]} {...AXIS} tickFormatter={v => formatRatio(v, unit)}
                 allowDuplicatedCategory={false} />
          <YAxis dataKey="points" domain={[0, 100]} ticks={[0, 50, 100]} width={32} {...AXIS} axisLine={false} />
          <Tooltip cursor={{ stroke: 'var(--axis)', strokeWidth: 1 }}
                   content={({ active, payload }) => active && payload?.length ? (
                     <TooltipBox title={formatRatio(payload[0].payload.value, unit)}
                                 rows={payload.map((p, i) => ({ label: String(p.name), value: `${Math.round(Number(p.value))} pts`, color: SERIES[i] }))} />
                   ) : null} />
          {series.map((s, i) => (
            <Line key={s.key} data={s.data} dataKey="points" name={s.label} stroke={SERIES[i]} strokeWidth={2}
                  dot={false} type="linear" isAnimationActive={false} />
          ))}
          <ReferenceDot x={value} y={points} r={6} fill="var(--text)" stroke="var(--surface)" strokeWidth={2}
                        label={{ value: `${formatRatio(value, unit)} → ${points} pts`, position: 'top', fill: 'var(--text)', fontSize: 11 }} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

/**
 * « Où se situe la société ? » Une bande par indicateur : chaque point est une société cotée.
 * Gris : tout le marché ; bleu : le même secteur ; orange cerclé : la société.
 * Les valeurs extrêmes sont ramenées au bord (sinon une seule société aberrante écrase tout).
 */
export function StripPlot({ values, sector, company, unit, higherIsBetter }: {
  values: number[]
  sector: number[]
  company?: number
  unit: RatioDefinition['unit']
  higherIsBetter: boolean
}) {
  const [ref, width] = useWidth<HTMLDivElement>()
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  const q = (p: number) => sorted[Math.min(sorted.length - 1, Math.max(0, Math.round(p * (sorted.length - 1))))]
  let lo = q(0.05)
  let hi = q(0.95)
  if (company !== undefined) {
    lo = Math.min(lo, company)
    hi = Math.max(hi, company)
  }
  if (hi === lo) hi = lo + 1
  const W = width
  const x = (v: number) => 8 + ((Math.min(Math.max(v, lo), hi) - lo) / (hi - lo)) * (W - 16)
  const median = q(0.5)
  return (
    <div ref={ref}>
    <svg className="strip" viewBox={`0 0 ${W} 34`} role="img"
         aria-label={company !== undefined ? `Valeur ${formatRatio(company, unit)}, médiane du marché ${formatRatio(median, unit)}` : undefined}>
      <line x1={8} x2={W - 8} y1={17} y2={17} stroke="var(--grid)" strokeWidth={1} />
      <line x1={x(median)} x2={x(median)} y1={6} y2={28} stroke="var(--axis)" strokeWidth={1} />
      {values.map((v, i) => (
        <circle key={`u${i}`} cx={x(v)} cy={17} r={4} fill="var(--dot-muted)" opacity={0.7}
                ><title>{formatRatio(v, unit)}</title></circle>
      ))}
      {sector.map((v, i) => (
        <circle key={`s${i}`} cx={x(v)} cy={17} r={4.5} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={1}>
          <title>Même secteur : {formatRatio(v, unit)}</title>
        </circle>
      ))}
      {company !== undefined && (
        <circle cx={x(company)} cy={17} r={7} fill="var(--series-2)" stroke="var(--surface)" strokeWidth={2}>
          <title>La société : {formatRatio(company, unit)}</title>
        </circle>
      )}
      <text x={10} y={33} fontSize={10} fill="var(--axis)">{higherIsBetter ? 'moins bien' : 'mieux'}</text>
      <text x={W - 10} y={33} fontSize={10} fill="var(--axis)" textAnchor="end">{higherIsBetter ? 'mieux' : 'moins bien'}</text>
    </svg>
    </div>
  )
}

/** Sens d'un indicateur : d'après son barème, plus de points quand la valeur monte ? */
function higherIsBetter(def: RatioDefinition): boolean {
  const s = def.scale
  return s[s.length - 1].points >= s[0].points
}

export function StripLegend({ withCompany = true }: { withCompany?: boolean }) {
  return (
    <Legend items={[
      ...(withCompany ? [{ label: 'La société', color: 'var(--series-2)' }] : []),
      { label: 'Même secteur', color: 'var(--series-1)' },
      { label: 'Tout le marché (trait : médiane)', color: 'var(--dot-muted)', term: 'MEDIAN' },
    ]} />
  )
}

/** Toutes les bandes d'un ensemble d'indicateurs, pour une société ou un secteur. */
export function StripList({ ratioIds, definitions, universe, sectorCode, isin }: {
  ratioIds: string[]
  definitions: RatioDefinition[]
  universe: UniverseEntry[]
  sectorCode: string | null
  isin?: string
}) {
  const self = isin ? universe.find(u => u.isin === isin) : undefined
  const sectorEntries = universe.filter(u => sectorCode && u.supersectorCode === sectorCode && u.isin !== isin)
  const median = (vs: number[]) => {
    if (vs.length === 0) return undefined
    const s = [...vs].sort((a, b) => a - b)
    return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2
  }
  return (
    <>
      <StripLegend withCompany={isin !== undefined} />
      <ul className="strips">
        {ratioIds.map(id => {
          const def = definitions.find(d => d.id === id)
          if (!def) return null
          const all = universe.map(u => u.ratios[id]).filter((v): v is number => typeof v === 'number')
          const sec = sectorEntries.map(u => u.ratios[id]).filter((v): v is number => typeof v === 'number')
          const own = self?.ratios[id]
          const sectorMedian = median(sectorCode ? [...sec, ...(own !== undefined ? [own] : [])] : [])
          return (
            <li key={id}>
              <span className="strip-label">
                <Term id={id}>{def.label}</Term>
                <small>
                  {own !== undefined && <>Société {formatRatio(own, def.unit)} · </>}
                  {sectorMedian !== undefined && <>secteur {formatRatio(sectorMedian, def.unit)} · </>}
                  marché {formatRatio(median(all) ?? 0, def.unit)}
                </small>
              </span>
              <StripPlot values={all} sector={sec} company={own} unit={def.unit} higherIsBetter={higherIsBetter(def)} />
            </li>
          )
        })}
      </ul>
    </>
  )
}
