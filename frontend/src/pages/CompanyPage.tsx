import { useParams, useSearchParams } from 'react-router'
import { HttpError, useCompany, useCompanyScore, useQuote, useRatioDefinitions } from '../api/queries'
import type { Contribution, RatioDefinition, Score } from '../api/types'
import { FreshnessBadge, PointsBar, ScoreBadge } from '../components/Badges'
import { parseProfile, ProfileTabs } from '../components/ProfileTabs'
import { FinancialsTable } from '../components/FinancialsTable'
import { formatDate, formatDateTime, formatPercent, formatPrice, formatRatio } from '../format'

/** La fiche d'une société : identité, cours, score expliqué, comptes. */
export function CompanyPage() {
  const { isin = '' } = useParams() // le :isin de la route /societe/:isin
  const [params, setParams] = useSearchParams()
  const profile = parseProfile(params.get('profil'))

  const company = useCompany(isin)
  const quote = useQuote(isin)
  const score = useCompanyScore(isin, profile)

  if (company.error instanceof HttpError && company.error.status === 404) {
    return <p className="error">Aucune société avec l'ISIN {isin}.</p>
  }
  if (!company.data) {
    return <p className="muted">{company.error ? `Erreur : ${company.error.message}` : 'Chargement…'}</p>
  }
  const c = company.data

  return (
    <>
      <header className="company-header">
        <div>
          <h1>{c.name} <span className="muted ticker">{c.ticker}</span></h1>
          <p className="muted">
            {c.icb ? `${c.icb.industry} › ${c.icb.supersector} › ${c.icb.sector}` : 'Secteur inconnu'}
          </p>
          <dl className="identity">
            <dt>ISIN</dt><dd>{c.isin}</dd>
            <dt>LEI</dt><dd>{c.lei ?? '—'}</dd>
            <dt>Cotation</dt><dd>{c.market}</dd>
          </dl>
        </div>
        <div className="quote">
          {quote.data ? (
            <>
              <strong>{formatPrice(quote.data.price, quote.data.currency)}</strong>
              <span className="muted">
                {quote.data.source === 'YAHOO' ? 'Cours différé (~15 min)' : 'Dernière clôture'} ·{' '}
                {formatDateTime(quote.data.asOf)}
              </span>
            </>
          ) : (
            <span className="muted">{quote.isPending ? '…' : 'Pas de cours'}</span>
          )}
        </div>
      </header>

      <ProfileTabs value={profile} onChange={p => setParams({ profil: p })} />

      <div className="company-grid">
        <section className="card">
          {score.data ? <ScoreDetail score={score.data.score} /> :
            <p className="muted">{score.error ? 'Pas de données financières pour cette société.' : 'Chargement…'}</p>}
        </section>

        {/* L'analyse du LLM sera affichée à côté du score, jamais dedans (document de conception). */}
        <aside className="card">
          <h2>Analyse qualitative</h2>
          <p className="muted">
            Bientôt : un résumé du rapport annuel, l'avantage concurrentiel et ses risques, rédigés par un
            modèle de langage local, avec leurs citations du rapport.
          </p>
        </aside>
      </div>

      <section className="card">
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
function ScoreDetail({ score }: { score: Score }) {
  const { data: definitions } = useRatioDefinitions()
  const def = (id: string) => definitions?.find(d => d.id === id)
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

      {sorted.length > 0 && (
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
        </details>
      </td>
      <td className="num">{formatRatio(c.value, unit)}</td>
      <td className="score-cell"><span className="points">{c.points}</span><PointsBar points={c.points} /></td>
      <td className="num">{formatPercent(c.share)}</td>
      <td className="num">{c.contribution.toLocaleString('fr-FR', { maximumFractionDigits: 1 })}</td>
    </tr>
  )
}
