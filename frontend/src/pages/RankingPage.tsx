import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useRanking } from '../api/queries'
import { FreshnessBadge, PointsBar, ScoreBadge } from '../components/Badges'
import { parseProfile, ProfileTabs } from '../components/ProfileTabs'
import { formatDate, formatPercent } from '../format'

/** Le classement (« screener ») : toutes les sociétés notées selon le profil choisi. */
export function RankingPage() {
  // Le profil vit dans l'URL (/?profil=VALUE) : bouton « retour » et liens partagés le gardent.
  const [params, setParams] = useSearchParams()
  const profile = parseProfile(params.get('profil'))
  const { data: ranking, isPending, error } = useRanking(profile)
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
      <ProfileTabs value={profile} onChange={p => setParams({ profil: p })} />

      <div className="toolbar">
        <label>
          Secteur{' '}
          <select value={sector} onChange={e => setSector(e.target.value)}>
            <option value="">Tous les secteurs</option>
            {sectors.map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        {ranking && <span className="muted">{scored.length} sociétés notées</span>}
      </div>

      {isPending && <p className="muted">Chargement…</p>}
      {error && <p className="error">Impossible de charger le classement : {error.message}</p>}

      {ranking && (
        <div className="table-wrap">
          <table className="ranking">
            <thead>
              <tr>
                <th className="num">#</th>
                <th>Société</th>
                <th className="hide-narrow">Secteur</th>
                <th>Score</th>
                <th className="hide-narrow" title="Part des indicateurs du profil que l'on a pu calculer">Données</th>
                <th className="hide-narrow">Exercice</th>
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
            {showUnscored ? '▾' : '▸'} {unscored.length} sociétés non notées (données insuffisantes ou trop anciennes)
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
