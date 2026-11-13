import { Link, useParams, useSearchParams } from 'react-router'
import { useProfiles, useRatioDefinitions, useUniverse } from '../api/queries'
import type { ProfileId, UniverseEntry } from '../api/types'
import { ChartCard } from '../charts/common'
import { ProfileScatter, ScoreHistogram } from '../charts/MarketCharts'
import { ScoreBars, StripList } from '../charts/ScoreCharts'
import { ProfileTabs } from '../components/ProfileTabs'
import { parseProfile } from '../profile'
import { HowToRead, Term } from '../components/Term'
import { formatRatio, weightsFor } from '../format'

const KEY_RATIOS = ['OPERATING_MARGIN', 'NET_MARGIN', 'ROE', 'NET_DEBT_TO_EQUITY', 'FCF_MARGIN', 'PER', 'DIVIDEND_YIELD']
const SHORT_LABEL: Record<string, string> = {
  OPERATING_MARGIN: 'Marge opé.', NET_MARGIN: 'Marge nette', ROE: 'ROE', NET_DEBT_TO_EQUITY: 'Dette nette / CP',
  FCF_MARGIN: 'Marge FCF', PER: 'PER', DIVIDEND_YIELD: 'Rdt dividende',
}

function median(values: number[]): number | null {
  if (values.length === 0) return null
  const s = [...values].sort((a, b) => a - b)
  return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2
}

interface Sector {
  code: string
  label: string
  companies: UniverseEntry[]
}

function groupBySector(universe: UniverseEntry[]): Sector[] {
  const map = new Map<string, Sector>()
  for (const u of universe) {
    if (!u.supersectorCode || !u.supersector) continue
    const s = map.get(u.supersectorCode) ?? { code: u.supersectorCode, label: u.supersector, companies: [] }
    s.companies.push(u)
    map.set(u.supersectorCode, s)
  }
  return [...map.values()]
}

const scoresOf = (companies: UniverseEntry[], profile: ProfileId) =>
  companies.map(c => c.scores[profile]).filter((s): s is number => s !== null)

/** Les 20 super-secteurs ICB comparés : quel secteur est le mieux noté dans ce profil ? */
export function SectorsPage() {
  const [params, setParams] = useSearchParams()
  const profile = parseProfile(params.get('profil'))
  const { data: universe, isPending } = useUniverse()
  const { data: definitions } = useRatioDefinitions()

  if (isPending || !universe) return <><h1>Secteurs</h1><p className="muted">Chargement…</p></>
  const sectors = groupBySector(universe)
    .map(s => ({ ...s, median: median(scoresOf(s.companies, profile)) }))
    .sort((a, b) => (b.median ?? -1) - (a.median ?? -1))
  const def = (id: string) => definitions?.find(d => d.id === id)

  return (
    <>
      <h1>Secteurs</h1>
      <HowToRead title="Comment lire cette page ?">
        <p>
          Les sociétés sont regroupées par <Term id="SUPERSECTOR">super-secteur</Term> (classification{' '}
          <Term id="ICB">ICB</Term>) : des entreprises qui font le même métier. Comparer un secteur à un autre montre
          où se trouvent, en ce moment, les entreprises qui correspondent le mieux au profil choisi.
        </p>
        <ul>
          <li><strong>Score médian</strong> : la note de la société « du milieu » du secteur (la <Term id="MEDIAN">médiane</Term>). Le nombre entre parenthèses est le nombre de sociétés.</li>
          <li><strong>Chiffres types</strong> : la médiane de quelques indicateurs clés dans chaque secteur. Utile pour savoir ce qui est « normal » dans un métier avant de juger une société.</li>
        </ul>
        <p className="muted">Un secteur sans barre (« — ») n'a aucune société notée dans ce profil : c'est le cas des banques et assurances pour les profils qui demandent des chiffres qu'elles ne publient pas.</p>
      </HowToRead>
      <ProfileTabs value={profile} onChange={p => setParams({ profil: p })} />
      <div style={{ display: 'grid', gap: '1rem' }}>
        <ChartCard title="Score médian par secteur" help="MEDIAN"
                   subtitle="La note de la société « du milieu » de chaque secteur. Cliquer sur un secteur pour voir ses sociétés.">
          <ScoreBars items={sectors.map(s => ({
            label: `${s.label} (${s.companies.length})`, score: s.median === null ? null : Math.round(s.median),
            to: `/secteurs/${s.code}?profil=${profile}`,
          }))} />
        </ChartCard>
        <ChartCard title="Chiffres types de chaque secteur" help="MEDIAN"
                   subtitle="Médiane des sociétés de chaque secteur. Survolez un en-tête de colonne pour sa définition.">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th><Term id="SUPERSECTOR">Secteur</Term></th>
                  {KEY_RATIOS.map(id => <th key={id} className="num"><Term id={id}>{SHORT_LABEL[id]}</Term></th>)}
                </tr>
              </thead>
              <tbody>
                {[...sectors].sort((a, b) => a.label.localeCompare(b.label)).map(s => (
                  <tr key={s.code}>
                    <td><Link to={`/secteurs/${s.code}?profil=${profile}`}>{s.label}</Link></td>
                    {KEY_RATIOS.map(id => {
                      const m = median(s.companies.map(c => c.ratios[id]).filter((v): v is number => typeof v === 'number'))
                      return <td key={id} className="num">{m === null ? '—' : formatRatio(m, def(id)?.unit ?? 'MULTIPLE')}</td>
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </ChartCard>
      </div>
    </>
  )
}

/** Un secteur : ses sociétés classées, et leurs indicateurs face au reste du marché. */
export function SectorPage() {
  const { code = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const profile = parseProfile(params.get('profil'))
  const { data: universe, isPending } = useUniverse()
  const { data: definitions } = useRatioDefinitions()
  const { data: profiles } = useProfiles()

  if (isPending || !universe) return <p className="muted">Chargement…</p>
  const sector = groupBySector(universe).find(s => s.code === code)
  if (!sector) return <p>Secteur inconnu. <Link to="/secteurs">Tous les secteurs</Link></p>

  const ranked = [...sector.companies].sort((a, b) => (b.scores[profile] ?? -1) - (a.scores[profile] ?? -1))
  const weights = weightsFor(profiles?.find(p => p.id === profile), code)
  // D'abord les indicateurs du profil (par poids), puis tous les autres.
  const ratioIds = (definitions ?? []).map(d => d.id)
    .sort((a, b) => (weights[b] ?? 0) - (weights[a] ?? 0))

  return (
    <>
      <p className="muted"><Link to={`/secteurs?profil=${profile}`}>Secteurs</Link> ›</p>
      <h1>{sector.label} <span className="muted ticker">{sector.companies.length} sociétés</span></h1>
      <ProfileTabs value={profile} onChange={p => setParams({ profil: p })} />

      <div className="layout-2">
        <ChartCard title="Classement du secteur" help="SCORE" subtitle="Le score de chaque société du secteur dans le profil choisi. Cliquer sur une société pour sa fiche.">
          <ScoreBars items={ranked.map(c => ({ label: c.name, score: c.scores[profile], to: `/societe/${c.isin}?profil=${profile}` }))} />
        </ChartCard>
        <div style={{ display: 'grid', gap: '1rem' }}>
          <ScoreHistogram title="Répartition des scores du secteur" scores={scoresOf(sector.companies, profile)} />
          <ProfileScatter universe={universe} profiles={profiles} highlightSector={sector.label}
                          y={profile} x={profile === 'VALUE' ? 'QUALITY' : 'VALUE'} />
        </div>
      </div>

      <section className="card" style={{ marginTop: '1rem' }}>
        <h2>Les indicateurs du secteur face au marché</h2>
        <p className="muted">
          Une <Term id="STRIP">bande</Term> par indicateur : en bleu les sociétés du secteur, en gris tout le marché, le trait
          vertical marque la <Term id="MEDIAN">médiane</Term> du marché. Les indicateurs du profil viennent en premier ; survolez
          un nom pour sa définition.
        </p>
        {definitions && <StripList ratioIds={ratioIds} definitions={definitions} universe={universe} sectorCode={code} />}
      </section>
    </>
  )
}
