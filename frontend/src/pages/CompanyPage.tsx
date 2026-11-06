import { Link, useParams, useSearchParams } from 'react-router'
import {
  HttpError, useCompany, useCompanyScore, useProfiles, useRatioDefinitions, useUniverse,
} from '../api/queries'
import type { Contribution, RatioDefinition, Score } from '../api/types'
import { HistoryCharts } from '../charts/HistoryCharts'
import { PriceChart } from '../charts/PriceChart'
import { ProfileScores, ScaleChart, ScoreBars, StripList } from '../charts/ScoreCharts'
import { FreshnessBadge, PointsBar, ScoreBadge } from '../components/Badges'
import { FinancialsTable } from '../components/FinancialsTable'
import { LiveQuote } from '../components/LiveQuote'
import { parseProfile, ProfileTabs } from '../components/ProfileTabs'
import { formatDate, formatPercent, formatRatio } from '../format'

/** La fiche d'une société : identité, cours en direct, score expliqué, évolution, comptes. */
export function CompanyPage() {
  const { isin = '' } = useParams() // le :isin de la route /societe/:isin
  const [params, setParams] = useSearchParams()
  const profile = parseProfile(params.get('profil'))

  const company = useCompany(isin)
  const score = useCompanyScore(isin, profile)
  const { data: profiles } = useProfiles()
  const { data: definitions } = useRatioDefinitions()
  const { data: universe } = useUniverse()

  if (company.error instanceof HttpError && company.error.status === 404) {
    return <p className="error">Aucune société avec l'ISIN {isin}.</p>
  }
  if (!company.data) {
    return <p className="muted">{company.error ? `Erreur : ${company.error.message}` : 'Chargement…'}</p>
  }
  const c = company.data
  const entry = universe?.find(u => u.isin === isin)
  const profileRatios = Object.entries(profiles?.find(p => p.id === profile)?.weights ?? {})
    .sort((a, b) => b[1] - a[1]).map(([id]) => id)

  return (
    <>
      <header className="company-header">
        <div>
          <h1>{c.name} <span className="muted ticker">{c.ticker}</span></h1>
          <p className="muted">
            {c.icb ? (
              <>{c.icb.industry} › <Link to={`/secteurs/${c.icb.code.substring(0, 4)}`}>{c.icb.supersector}</Link> › {c.icb.sector}</>
            ) : 'Secteur inconnu'}
          </p>
          <dl className="identity">
            <dt>ISIN</dt><dd>{c.isin}</dd>
            <dt>LEI</dt><dd>{c.lei ?? '—'}</dd>
            <dt>Cotation</dt><dd>{c.market}</dd>
          </dl>
        </div>
        <LiveQuote isin={isin} />
      </header>

      <PriceChart isin={isin} currency="EUR" />

      <h2 className="section-title">Score</h2>
      <ProfileTabs value={profile} onChange={p => setParams({ profil: p })} />

      <div className="company-grid">
        <section className="card">
          {score.data ? <ScoreDetail score={score.data.score} definitions={definitions} /> :
            <p className="muted">{score.error ? 'Pas de données financières pour cette société.' : 'Chargement…'}</p>}
        </section>

        <aside>
          <section className="card">
            <h2>Ses notes dans les 4 profils</h2>
            {entry ? <ProfileScores entry={entry} profiles={profiles} current={profile} />
              : <p className="muted">{universe ? 'Pas de score.' : 'Chargement…'}</p>}
          </section>
          {/* L'analyse du LLM sera affichée à côté du score, jamais dedans (document de conception). */}
          <section className="card">
            <h2>Analyse qualitative</h2>
            <p className="muted">
              Bientôt : un résumé du rapport annuel, l'avantage concurrentiel et ses risques, rédigés par un
              modèle de langage local, avec leurs citations du rapport.
            </p>
          </section>
        </aside>
      </div>

      <section className="card">
        <h2>Face à son secteur et au marché</h2>
        <p className="muted">
          Les indicateurs du profil, du plus important au moins important. Chaque point est une société cotée à Paris.
        </p>
        {universe && definitions ? (
          <StripList ratioIds={profileRatios} definitions={definitions} universe={universe}
                     sectorCode={entry?.supersectorCode ?? null} isin={isin} />
        ) : <p className="muted">Chargement…</p>}
      </section>

      <h2 className="section-title">Évolution</h2>
      <HistoryCharts isin={isin} />

      <section className="card" style={{ marginTop: '1rem' }}>
        <h2>Comptes annuels</h2>
        <FinancialsTable isin={isin} />
      </section>
    </>
  )
}

/**
 * Le score et ce qui le compose. Chaque indicateur rapporte des points (0 à 100, selon son
 * barème) multipliés par son poids dans le profil : la somme des contributions donne le score.
 */
function ScoreDetail({ score, definitions }: { score: Score; definitions: RatioDefinition[] | undefined }) {
  const def = (id: string) => definitions?.find(d => d.id === id)
  const familyLabel = (family: string) => definitions?.find(d => d.family === family)?.familyLabel ?? family
  const sorted = [...score.contributions].sort((a, b) => b.contribution - a.contribution)

  return (
    <>
      <div className="score-summary">
        <ScoreBadge score={score.score} size="large" />
        <div>
          <p>
            {score.score === null
              ? 'Pas de score : données insuffisantes ou trop anciennes.'
              : `Note sur 100, calculée avec ${formatPercent(score.coverage)} des indicateurs du profil.`}
          </p>
          {score.fiscalYearEnd && (
            <p className="muted">
              Exercice clos le {formatDate(score.fiscalYearEnd)} <FreshnessBadge freshness={score.freshness} />
            </p>
          )}
        </div>
      </div>

      {Object.keys(score.familyScore).length > 0 && (
        <>
          <h3>Par famille d'indicateurs</h3>
          <ScoreBars items={Object.entries(score.familyScore).map(([f, s]) => ({ label: familyLabel(f), score: s }))} />
        </>
      )}

      {sorted.length > 0 && (
        <>
          <h3>Indicateur par indicateur</h3>
          <p className="muted small">Cliquer sur un indicateur pour voir son barème et où se place la société.</p>
          <div className="table-wrap">
          <table className="contributions">
            <thead>
              <tr>
                <th>Indicateur</th>
                <th className="num">Valeur</th>
                <th>Points</th>
                <th className="num" title="Part de l'indicateur dans le score (poids du profil)">Poids</th>
                <th className="num" title="Points × poids : ce que l'indicateur apporte au score">Apport</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map(c => <ContributionRow key={c.ratio} c={c} def={def(c.ratio)} />)}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={4}>Score</td>
                <td className="num"><strong>{score.score ?? '—'}</strong></td>
              </tr>
            </tfoot>
          </table>
          </div>
        </>
      )}

      {score.missing.length > 0 && (
        <div className="missing">
          <h3>Non pris en compte</h3>
          <p className="muted">Leur poids est réparti sur les autres indicateurs.</p>
          <ul>
            {score.missing.map(m => (
              <li key={m.ratio}><strong>{def(m.ratio)?.label ?? m.ratio}</strong> : {m.reason}</li>
            ))}
          </ul>
        </div>
      )}
    </>
  )
}

function ContributionRow({ c, def }: { c: Contribution; def: RatioDefinition | undefined }) {
  const unit = def?.unit ?? 'MULTIPLE'
  const adj = c.sectorAdjustment
  return (
    <tr>
      <td>
        {/* <details> : un bloc dépliable natif du navigateur, sans code JavaScript. */}
        <details>
          <summary>{def?.label ?? c.ratio}</summary>
          <p className="muted">{def?.description}</p>
          {adj && (
            <p className="adjustment">
              Barème adapté au secteur « {adj.label} » : médiane {formatRatio(adj.sectorMedian, unit)} sur{' '}
              {adj.sampleSize} sociétés, contre {formatRatio(adj.universeMedian, unit)} pour l'ensemble
              (barème × {adj.factor.toLocaleString('fr-FR', { maximumFractionDigits: 2 })}).
            </p>
          )}
          {def && <ScaleChart def={def} value={c.value} points={c.points} adjustment={adj} />}
        </details>
      </td>
      <td className="num">{formatRatio(c.value, unit)}</td>
      <td className="score-cell"><span className="points">{c.points}</span><PointsBar points={c.points} /></td>
      <td className="num">{formatPercent(c.share)}</td>
      <td className="num">{c.contribution.toLocaleString('fr-FR', { maximumFractionDigits: 1 })}</td>
    </tr>
  )
}

