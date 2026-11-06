import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useProfiles, useRanking, useUniverse } from '../api/queries'
import { ProfileScatter, ScoreHistogram } from '../charts/MarketCharts'
import { FreshnessBadge, PointsBar, ScoreBadge } from '../components/Badges'
import { parseProfile, ProfileTabs } from '../components/ProfileTabs'
import { HowToRead, Term } from '../components/Term'
import { formatDate, formatPercent } from '../format'

/** Le classement (« screener ») : toutes les sociétés notées selon le profil choisi. */
export function RankingPage() {
  // Le profil vit dans l'URL (/?profil=VALUE) : bouton « retour » et liens partagés le gardent.
  const [params, setParams] = useSearchParams()
  const profile = parseProfile(params.get('profil'))
  const { data: ranking, isPending, error } = useRanking(profile)
  const { data: universe } = useUniverse()
  const { data: profiles } = useProfiles()
  const [sector, setSector] = useState('')
  const [showUnscored, setShowUnscored] = useState(false)

  const sectors = useMemo(
    () => [...new Set((ranking ?? []).map(r => r.sector).filter((s): s is string => s !== null))].sort(),
    [ranking])

  const inSector = (ranking ?? []).filter(r => sector === '' || r.sector === sector)
  const scored = inSector.filter(r => r.score !== null)
  const unscored = inSector.filter(r => r.score === null)

  return (
    <>
      <h1>Classement</h1>
      <HowToRead title="Comment lire ce classement ?">
        <p>
          Toutes les sociétés cotées à Paris dont on a les comptes, de la mieux à la moins bien notée. La note (le{' '}
          <Term id="SCORE">score</Term>, sur 100) dit à quel point la société correspond au{' '}
          <Term id="PROFILE">profil</Term> choisi dans les onglets ci-dessous : ce n'est pas une prévision du cours.
        </p>
        <ol>
          <li><strong>Choisissez un profil</strong> : Qualité, Croissance, Dividende ou Value. Le classement change, car chaque profil regarde des chiffres différents.</li>
          <li>
            <strong>Lisez le score</strong> : <span className="score score-good score-small">72</span> vert à partir de 60 (correspond bien au profil),{' '}
            <span className="score score-mid score-small">50</span> orange de 40 à 59,{' '}
            <span className="score score-bad score-small">25</span> rouge sous 40.
          </li>
          <li>
            <strong>Vérifiez sa fiabilité</strong> : la colonne <Term id="COVERAGE">Données</Term> indique quelle part des indicateurs a pu être{' '}
            calculée, et la colonne <Term id="FISCAL_YEAR">Exercice</Term> si les comptes sont{' '}
            <Term id="UP_TO_DATE">à jour</Term> ou <Term id="LATE">en retard</Term>.
          </li>
          <li><strong>Cliquez sur une société</strong> pour comprendre d'où vient sa note, indicateur par indicateur.</li>
        </ol>
        <p className="muted">Les deux graphiques résument le marché : combien de sociétés par niveau de note, et comment se combinent deux profils.</p>
      </HowToRead>
      <ProfileTabs value={profile} onChange={p => setParams({ profil: p })} />

      <div className="toolbar">
        <label>
          <Term id="SUPERSECTOR">Secteur</Term>{' '}
          <select value={sector} onChange={e => setSector(e.target.value)}>
            <option value="">Tous les secteurs</option>
            {sectors.map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        {ranking && <span className="muted">{scored.length} sociétés notées</span>}
      </div>

      {ranking && (
        <div className="layout-2" style={{ marginBottom: '1rem' }}>
          <ScoreHistogram title="Répartition des scores" scores={scored.map(r => r.score!)} />
          {universe ? (
            <ProfileScatter universe={universe} profiles={profiles} highlightSector={sector || undefined}
                            y={profile} x={profile === 'VALUE' ? 'QUALITY' : 'VALUE'} />
          ) : <div className="chart-card"><p className="muted">Chargement du nuage de points…</p></div>}
        </div>
      )}

      {isPending && <p className="muted">Chargement…</p>}
      {error && <p className="error">Impossible de charger le classement : {error.message}</p>}

      {ranking && (
        <div className="table-wrap">
          <table className="ranking">
            <thead>
              <tr>
                <th className="num">#</th>
                <th>Société</th>
                <th className="hide-narrow"><Term id="SUPERSECTOR">Secteur</Term></th>
                <th><Term id="SCORE" /></th>
                <th className="hide-narrow"><Term id="COVERAGE">Données</Term></th>
                <th className="hide-narrow"><Term id="FRESHNESS">Exercice</Term></th>
              </tr>
            </thead>
            <tbody>
              {scored.map((r, i) => (
                <tr key={r.isin}>
                  <td className="num muted">{i + 1}</td>
                  <td>
                    <Link to={`/societe/${r.isin}?profil=${profile}`}>{r.name}</Link>
                    <span className="muted ticker">{r.ticker}</span>
                  </td>
                  <td className="hide-narrow muted">{r.sector ?? '—'}</td>
                  <td className="score-cell"><ScoreBadge score={r.score} /><PointsBar points={r.score ?? 0} /></td>
                  <td className="hide-narrow num">{formatPercent(r.coverage)}</td>
                  <td className="hide-narrow">
                    {r.fiscalYearEnd && formatDate(r.fiscalYearEnd)} <FreshnessBadge freshness={r.freshness} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {unscored.length > 0 && (
        <section className="unscored">
          <button className="link" onClick={() => setShowUnscored(!showUnscored)}>
            {showUnscored ? '▾' : '▸'} {unscored.length} sociétés non notées (moins de 60 % des indicateurs calculables, ou comptes de plus de 28 mois)
          </button>
          {showUnscored && (
            <ul className="columns">
              {unscored.map(r => (
                <li key={r.isin}><Link to={`/societe/${r.isin}?profil=${profile}`}>{r.name}</Link></li>
              ))}
            </ul>
          )}
        </section>
      )}
    </>
  )
}
