import { Link, useParams, useSearchParams } from 'react-router'
import {
  HttpError, useCompany, useCompanyScore, useProfiles, useQuote, useRatioDefinitions, useUniverse,
} from '../api/queries'
import type { Contribution, RatioDefinition, Score } from '../api/types'
import { HistoryCharts } from '../charts/HistoryCharts'
import { PriceChart } from '../charts/PriceChart'
import { ScoreHistoryChart } from '../charts/ScoreHistoryChart'
import { ProfileScores, ScaleChart, ScoreBars, StripList } from '../charts/ScoreCharts'
import { FreshnessBadge, PointsBar, ScoreBadge } from '../components/Badges'
import { FinancialsTable } from '../components/FinancialsTable'
import { TtmSection } from '../components/TtmSection'
import { LiveQuote } from '../components/LiveQuote'
import { ProfileTabs } from '../components/ProfileTabs'
import { parseProfile } from '../profile'
import { ReportAnalysisSection } from '../components/ReportAnalysisSection'
import { HowToRead, Term } from '../components/Term'
import { glossary } from '../glossary'
import { BANKS_SUPERSECTOR, INSURANCE_SUPERSECTOR, formatDate, formatPercent, formatRatio, weightsFor } from '../format'

// Caisses régionales du Crédit Agricole (même règle que Company.isCooperativeCertificate côté Java)
const COOPERATIVE = /^CRCAM |CCI$/

/** La fiche d'une société : identité, cours en direct, score expliqué, évolution, comptes. */
export function CompanyPage() {
  const { isin = '' } = useParams() // le :isin de la route /societe/:isin
  const [params, setParams] = useSearchParams()
  const profile = parseProfile(params.get('profil'))

  const company = useCompany(isin)
  const score = useCompanyScore(isin, profile)
  const { data: quote } = useQuote(isin) // sa devise : SEK à Stockholm, GBP à Londres…
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
  const supersectorCode = c.icb?.code.substring(0, 4) ?? null
  const isBank = supersectorCode === BANKS_SUPERSECTOR
  const profileRatios = Object.entries(weightsFor(profiles?.find(p => p.id === profile), supersectorCode))
    .sort((a, b) => b[1] - a[1]).map(([id]) => id)

  return (
    <>
      <header className="company-header">
        <div>
          <h1>{c.name} <span className="muted ticker"><Term id="TICKER">{c.ticker}</Term></span></h1>
          <p className="muted">
            <Term id="ICB">Secteur</Term> :{' '}
            {c.icb ? (
              <>{c.icb.industry} › <Link to={`/secteurs/${c.icb.code.substring(0, 4)}`}>{c.icb.supersector}</Link> › {c.icb.sector}</>
            ) : 'inconnu'}
          </p>
          <dl className="identity">
            <dt><Term id="ISIN" /></dt><dd>{c.isin}</dd>
            <dt><Term id="LEI" /></dt><dd>{c.lei ?? '—'}</dd>
            <dt><Term id="EURONEXT_PARIS">Cotation</Term></dt><dd>{c.market}</dd>
          </dl>
        </div>
        <LiveQuote isin={isin} />
      </header>

      <HowToRead title="Comment lire cette fiche ?">
        <ol>
          <li><strong>Le cours</strong> : le prix de l'action, qui bouge en direct pendant la séance (9 h – 17 h 30).</li>
          <li>
            <strong>Le score</strong> : une note sur 100 qui dit si la société correspond au <Term id="PROFILE">profil</Term>{' '}
            choisi dans les onglets. Le tableau montre, indicateur par indicateur, d'où vient la note : cliquez sur un
            indicateur pour sa définition et son <Term id="SCALE">barème</Term>.
          </li>
          <li>
            <strong>Ce que dit le rapport annuel</strong> : ce que fait la société, ses avantages et ses risques, lus dans
            son rapport par une <Term id="REPORT_ANALYSIS">IA locale</Term>. Chaque point montre la phrase du rapport qui
            l'appuie : dépliez-le pour la lire.
          </li>
          <li><strong>Face à son secteur et au marché</strong> : où se place la société parmi toutes celles suivies en Europe.</li>
          <li>
            <strong>Évolution</strong> : l'<Term id="SCORE_HISTORY">historique de ses notes</Term>, puis ses chiffres année
            après année. Une tendance compte plus qu'une seule année.
          </li>
          <li><strong>Comptes annuels</strong> : les chiffres bruts de ses rapports officiels, d'où tout le reste est calculé.</li>
        </ol>
        <p className="muted">
          Chaque mot souligné en pointillés suivi d'un <span className="term-mark" aria-hidden>?</span> a sa définition :
          survolez-le ou touchez-le. Tout est aussi dans le <Link to="/lexique">lexique</Link>.
        </p>
      </HowToRead>

      <PriceChart isin={isin} currency={quote?.currency ?? 'EUR'} />

      <h2 className="section-title"><Term id="SCORE">Score</Term></h2>
      {isBank && (
        <p className="notice">
          <strong>Une banque se note à part.</strong> Sa dette est sa matière première et elle n'a pas de chiffre
          d'affaires : l'endettement ou la marge d'une entreprise classique n'ont pas de sens pour elle. Chaque profil
          la juge donc sur les indicateurs de son métier, comme le{' '}
          <Term id="COST_INCOME_RATIO">coefficient d'exploitation</Term> et le <Term id="COST_OF_RISK_RATIO">coût du risque</Term>.{' '}
          <Term id="BANK">En savoir plus</Term>
          {c.name && COOPERATIVE.test(c.name) && (
            <> Cette caisse régionale n'est cotée qu'à travers ses <Term id="CCI">certificats coopératifs</Term> :
              ses ratios de valorisation ne sont pas calculés.</>
          )}
        </p>
      )}
      {supersectorCode === INSURANCE_SUPERSECTOR && (
        <p className="notice">
          <strong>Une assurance se note à part.</strong> Son bilan est fait des placements qui couvrent les contrats
          de ses assurés : la dette, la liquidité ou le cash-flow libre d'une entreprise classique n'ont pas de sens
          pour elle. Chaque profil la juge donc sur sa rentabilité, sa régularité, sa croissance, son dividende et son
          prix. <Term id="INSURER">En savoir plus</Term>
        </p>
      )}
      <ProfileTabs value={profile} onChange={p => setParams({ profil: p })} />

      <div className="company-grid">
        <section className="card">
          {score.data ? <ScoreDetail score={score.data.score} definitions={definitions} /> :
            <p className="muted">{score.error ? 'Pas de données financières pour cette société.' : 'Chargement…'}</p>}
        </section>

        <aside>
          <section className="card">
            <h2>Ses notes dans les 4 <Term id="PROFILE">profils</Term></h2>
            <p className="muted small">Une même société peut être excellente pour un profil et mauvaise pour un autre : très rentable (Qualité) mais chère (Value), par exemple. Cliquer pour changer de profil.</p>
            {entry ? <ProfileScores entry={entry} profiles={profiles} current={profile} />
              : <p className="muted">{universe ? 'Pas de score.' : 'Chargement…'}</p>}
          </section>
        </aside>
      </div>

      {/* Affichée à côté du score, jamais prise en compte dedans (ADR 0011). */}
      <ReportAnalysisSection isin={isin} />

      <section className="card">
        <h2>Face à son secteur et au marché</h2>
        <p className="muted">
          Une <Term id="STRIP">bande</Term> par indicateur du profil, du plus important au moins important. Chaque point
          est une société suivie, en Europe : on voit d'un coup d'œil si la société fait mieux ou moins bien que ses
          concurrents (<Term id="SUPERSECTOR">même secteur</Term>) et que l'ensemble du marché.
        </p>
        {universe && definitions ? (
          <StripList ratioIds={profileRatios} definitions={definitions} universe={universe}
                     sectorCode={entry?.supersectorCode ?? null} isin={isin} />
        ) : <p className="muted">Chargement…</p>}
      </section>

      <h2 className="section-title">Évolution</h2>
      <p className="muted">Les chiffres de chaque <Term id="FISCAL_YEAR">exercice</Term>, du plus ancien au plus récent. Survolez un graphique pour lire les valeurs.</p>
      <div style={{ marginBottom: '1rem' }}>
        <ScoreHistoryChart isin={isin} profiles={profiles} current={profile} />
      </div>
      <HistoryCharts isin={isin} />

      {/* Pour information : le score reste calculé sur les comptes annuels (ADR 0015). */}
      <TtmSection isin={isin} />

      <section className="card" style={{ marginTop: '1rem' }}>
        <h2><Term id="ANNUAL_REPORT">Comptes annuels</Term></h2>
        <p className="muted small">Les chiffres tels que publiés dans les rapports officiels, d'où sont calculés tous les indicateurs. Survolez un montant pour voir son étiquette officielle (XBRL).</p>
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
              : <>Note sur 100, calculée avec <Term id="COVERAGE">{formatPercent(score.coverage)} des indicateurs</Term> du profil.</>}
          </p>
          {score.fiscalYearEnd && (
            <p className="muted">
              <Term id="FISCAL_YEAR">Exercice</Term> clos le {formatDate(score.fiscalYearEnd)} <FreshnessBadge freshness={score.freshness} explained />
            </p>
          )}
        </div>
      </div>

      {Object.keys(score.familyScore).length > 0 && (
        <>
          <h3>Par <Term id="FAMILY">famille d'indicateurs</Term></h3>
          <ScoreBars items={Object.entries(score.familyScore).map(([f, s]) => ({ label: familyLabel(f), score: s }))} />
        </>
      )}

      {sorted.length > 0 && (
        <>
          <h3>Indicateur par indicateur</h3>
          <p className="muted small">
            Cliquer sur le nom d'un indicateur (▶) pour voir sa définition, son calcul, son barème et où se place la société.
          </p>
          <div className="table-wrap">
          <table className="contributions">
            <thead>
              <tr>
                <th>Indicateur</th>
                <th className="num">Valeur</th>
                <th><Term id="POINTS" /></th>
                <th className="num"><Term id="WEIGHT" /></th>
                <th className="num"><Term id="CONTRIBUTION" /></th>
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
          <h3><Term id="MISSING" /></h3>
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
          <IndicatorHelp id={c.ratio} fallback={def?.description} />
          {adj && (
            <p className="adjustment">
              <Term id="SECTOR_ADJUSTMENT">Barème adapté au secteur</Term> « {adj.label} » : <Term id="MEDIAN">médiane</Term> {formatRatio(adj.sectorMedian, unit)} sur{' '}
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

/** La définition complète d'un indicateur, dépliée sous son nom dans le tableau du score. */
function IndicatorHelp({ id, fallback }: { id: string; fallback?: string }) {
  const entry = glossary(id)
  if (!entry) return <p className="muted">{fallback}</p>
  return (
    <div className="indicator-help">
      <p>{entry.short}</p>
      {entry.formula && <p className="term-formula"><span>Calcul :</span> {entry.formula}</p>}
      {entry.read && <p><strong>Comment le lire :</strong> {entry.read}</p>}
      {entry.watch && <p className="muted"><strong>Attention :</strong> {entry.watch}</p>}
    </div>
  )
}
