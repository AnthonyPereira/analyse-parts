import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useCustomRanking, useProfiles, useRatioDefinitions } from '../api/queries'
import type { ProfileId, RatioDefinition } from '../api/types'
import { ChartCard, Legend } from '../charts/common'
import { SERIES } from '../charts/theme'
import { HowToRead, Term } from '../components/Term'
import { FreshnessBadge, PointsBar, ScoreBadge } from '../components/Badges'

type Weights = Record<string, number>

// Les poids vivent dans l'URL (?poids=ROE:20,PER:10) : un réglage se partage par un simple lien.
const encode = (w: Weights) => Object.entries(w).filter(([, v]) => v > 0).map(([k, v]) => `${k}:${v}`).join(',')
const decode = (s: string | null): Weights | null => {
  if (!s) return null
  const w: Weights = {}
  for (const part of s.split(',')) {
    const [k, v] = part.split(':')
    const n = Number(v)
    if (k && n > 0) w[k] = Math.min(n, 100)
  }
  return Object.keys(w).length ? w : null
}

/** Attend que la valeur arrête de changer (ici pendant 400 ms) avant de la renvoyer. */
function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer) // une nouvelle valeur annule l'attente précédente
  }, [value, delay])
  return debounced
}

/**
 * Mode avancé : on règle soi-même l'importance de chaque indicateur. Le classement est
 * recalculé par le serveur avec exactement les mêmes barèmes que les profils prédéfinis.
 */
export function AdvancedPage() {
  const [params, setParams] = useSearchParams()
  const { data: profiles } = useProfiles()
  const { data: definitions } = useRatioDefinitions()
  const fromUrl = decode(params.get('poids'))
  const [weights, setWeights] = useState<Weights | null>(fromUrl)

  // Au premier affichage sans réglage dans l'URL, on part du profil Qualité.
  const quality = profiles?.find(p => p.id === 'QUALITY')?.weights
  const current: Weights = weights ?? quality ?? {}
  const debounced = useDebounced(current, 400)
  const { data: ranking, isFetching, error } = useCustomRanking(debounced)

  useEffect(() => {
    if (weights) setParams({ poids: encode(weights) }, { replace: true })
  }, [weights, setParams])

  const families = useMemo(() => {
    const map = new Map<string, { label: string; ratios: RatioDefinition[] }>()
    for (const d of definitions ?? []) {
      const f = map.get(d.family) ?? { label: d.familyLabel, ratios: [] }
      f.ratios.push(d)
      map.set(d.family, f)
    }
    return [...map.entries()]
  }, [definitions])

  const total = Object.values(current).reduce((a, b) => a + b, 0)
  const familyShare = families.map(([family, f], i) => ({
    family, label: f.label, color: SERIES[i % SERIES.length],
    weight: f.ratios.reduce((sum, r) => sum + (current[r.id] ?? 0), 0),
  }))

  const startFrom = (id: ProfileId) => setWeights({ ...(profiles?.find(p => p.id === id)?.weights ?? {}) })
  const setWeight = (id: string, value: number) => setWeights({ ...current, [id]: value })

  return (
    <>
      <h1>Mode avancé</h1>
      <HowToRead title="Comment utiliser le mode avancé ?">
        <p>
          Les 4 profils sont des réglages tout faits. Ici, vous créez le vôtre : chaque curseur règle le{' '}
          <Term id="WEIGHT">poids</Term> d'un indicateur, c'est-à-dire son importance dans le{' '}
          <Term id="SCORE">score</Term>. Le classement à droite se recalcule à chaque changement.
        </p>
        <ol>
          <li>Partez d'un profil existant avec les boutons, ou réglez tout vous-même.</li>
          <li>Montez les curseurs de ce qui compte pour vous, mettez à 0 ce qui ne vous intéresse pas. Seules les proportions comptent : 20 et 10 pèsent comme 2 et 1.</li>
          <li>La barre de couleur montre la part de chaque <Term id="FAMILY">famille</Term> : un réglage équilibré mélange plusieurs familles.</li>
          <li>L'adresse de la page garde votre réglage : copiez-la pour le partager ou le retrouver.</li>
        </ol>
        <p className="muted">
          Les <Term id="SCALE">barèmes</Term>, leur <Term id="SECTOR_ADJUSTMENT">adaptation au secteur</Term> et les règles de{' '}
          <Term id="FRESHNESS">fraîcheur</Term> restent ceux des profils. Survolez le nom d'un indicateur pour sa définition.
        </p>
      </HowToRead>

      <div className="toolbar">
        <span>Partir du profil :</span>
        {profiles?.map(p => <button key={p.id} className="tab" onClick={() => startFrom(p.id)}>{p.label}</button>)}
      </div>

      <div className="layout-2">
        <section className="card">
          <h2>Poids</h2>
          {total > 0 && (
            <>
              <p className="muted small">Répartition par famille</p>
              <div className="stack" role="img"
                   aria-label={familyShare.filter(f => f.weight > 0).map(f => `${f.label} ${Math.round(100 * f.weight / total)} %`).join(', ')}>
                {familyShare.filter(f => f.weight > 0).map(f => (
                  <span key={f.family} style={{ flexGrow: f.weight, background: f.color }}
                        title={`${f.label} : ${Math.round(100 * f.weight / total)} %`} />
                ))}
              </div>
              <Legend items={familyShare.filter(f => f.weight > 0).map(f => ({
                label: `${f.label} ${Math.round(100 * f.weight / total)} %`, color: f.color }))} />
            </>
          )}
          <div className="weights">
            {families.map(([family, f]) => (
              <fieldset key={family}>
                <legend>{f.label}</legend>
                {f.ratios.map(r => (
                  <label key={r.id} className="weight">
                    <span><Term id={r.id}>{r.label}</Term></span>
                    <input type="range" min={0} max={50} step={5} value={current[r.id] ?? 0}
                           onChange={e => setWeight(r.id, Number(e.target.value))} />
                    <span className="num">{total > 0 ? `${Math.round(100 * (current[r.id] ?? 0) / total)} %` : '—'}</span>
                  </label>
                ))}
              </fieldset>
            ))}
          </div>
        </section>

        <ChartCard title="Classement" subtitle={isFetching ? 'Calcul…' : `${ranking?.filter(r => r.score !== null).length ?? 0} sociétés notées`}>
          {error && <p className="error">Calcul impossible : {error.message}</p>}
          {total === 0 && <p className="muted">Donnez du poids à au moins un indicateur.</p>}
          <table className="ranking">
            <tbody>
              {ranking?.filter(r => r.score !== null).slice(0, 40).map((r, i) => (
                <tr key={r.isin}>
                  <td className="num muted">{i + 1}</td>
                  <td><Link to={`/societe/${r.isin}`}>{r.name}</Link> <FreshnessBadge freshness={r.freshness === 'UP_TO_DATE' ? null : r.freshness} /></td>
                  <td className="score-cell"><ScoreBadge score={r.score} /><PointsBar points={r.score ?? 0} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </ChartCard>
      </div>
    </>
  )
}
