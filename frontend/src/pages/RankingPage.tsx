import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useProfiles, useRankingPage, useUniverseScores } from '../api/queries'
import { ProfileScatter, ScoreHistogram } from '../charts/MarketCharts'
import { FreshnessBadge, PointsBar, ScoreBadge } from '../components/Badges'
import { Pagination } from '../components/Pagination'
import { ProfileTabs } from '../components/ProfileTabs'
import { parseProfile } from '../profile'
import { HowToRead, Term } from '../components/Term'
import { formatCountry, formatDate, formatPercent } from '../format'
import { useDebounced } from '../useDebounced'

const PAGE_SIZE = 50

/**
 * Le classement (« screener ») : les sociétés notées selon le profil choisi, 50 par page.
 * Le serveur filtre (secteur, pays, recherche) et découpe : la page ne reçoit que les lignes
 * affichées, plus l'histogramme et les listes des filtres déjà calculés.
 */
export function RankingPage() {
  // Profil, filtres et page vivent dans l'URL (/?profil=VALUE&secteur=Banks&page=2) :
  // bouton « retour » et liens partagés les gardent.
  const [params, setParams] = useSearchParams()
  const profile = parseProfile(params.get('profil'))
  const sector = params.get('secteur') ?? ''
  const country = params.get('pays') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '1') - 1) || 0
  const [search, setSearch] = useState(params.get('q') ?? '')
  const debouncedSearch = useDebounced(search.trim(), 300)

  const { data, isPending, isPlaceholderData, error } = useRankingPage(profile,
    { sector, country, q: debouncedSearch, page, size: PAGE_SIZE })
  const { data: universe } = useUniverseScores()
  const { data: profiles } = useProfiles()
  const [showUnscored, setShowUnscored] = useState(false)
  const [unscoredPage, setUnscoredPage] = useState(0)
  // Demandées seulement une fois la liste dépliée
  const unscored = useRankingPage(profile,
    { sector, country, q: debouncedSearch, scored: false, page: unscoredPage, size: 200 }, showUnscored)
  const tableTop = useRef<HTMLDivElement>(null)

  /** Change un paramètre de l'URL ; tout changement de filtre ramène à la première page. */
  const update = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value === '') next.delete(key)
      else next.set(key, value)
    }
    if (!('page' in changes)) next.delete('page')
    setParams(next)
    if (!('page' in changes)) setUnscoredPage(0)
  }

  // La recherche part dans l'URL une fois la frappe finie (pas à chaque lettre)
  const urlSearch = params.get('q') ?? ''
  useEffect(() => {
    if (debouncedSearch !== urlSearch) update({ q: debouncedSearch })
    // eslint-disable-next-line react-hooks/exhaustive-deps -- seule la frappe déclenche la mise à jour
  }, [debouncedSearch])

  /** Page suivante : on remonte en haut du tableau, pas en haut du site. */
  const goToPage = (p: number) => {
    update({ page: p === 0 ? '' : String(p + 1) })
    tableTop.current?.scrollIntoView({ block: 'start' })
  }

  return (
    <>
      <h1>Classement</h1>
      <HowToRead title="Comment lire ce classement ?">
        <p>
          Les sociétés cotées en Europe dont on a les comptes, de la mieux à la moins bien notée : celles des 7 places
          d'Euronext, et celles des autres places dont les rapports annuels officiels sont publiés en ligne (pas l'Allemagne).{' '} La note (le{' '}
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
        <p className="muted">
          Le classement s'affiche par pages de {PAGE_SIZE} sociétés (boutons sous le tableau). Pour en retrouver une,
          tapez son nom ou son code dans « Rechercher ». Les deux graphiques résument le marché : combien de sociétés
          par niveau de note, et comment se combinent deux profils.
        </p>
      </HowToRead>
      <ProfileTabs value={profile} onChange={p => update({ profil: p })} />

      <div className="toolbar">
        <label>
          Rechercher{' '}
          <input type="search" value={search} onChange={e => setSearch(e.target.value)}
                 placeholder="Nom, code, ISIN…" aria-label="Rechercher une société" />
        </label>
        <label>
          <Term id="SUPERSECTOR">Secteur</Term>{' '}
          <select value={sector} onChange={e => update({ secteur: e.target.value })}>
            <option value="">Tous les secteurs</option>
            {data?.sectors.map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        <label>
          <Term id="COUNTRY">Pays</Term>{' '}
          <select value={country} onChange={e => update({ pays: e.target.value })}>
            <option value="">Toute l'Europe</option>
            {[...(data?.countries ?? [])]
              .sort((a, b) => formatCountry(a).localeCompare(formatCountry(b), 'fr'))
              .map(c => <option key={c} value={c}>{formatCountry(c)}</option>)}
          </select>
        </label>
        {data && <span className="muted">{data.scoredCount} sociétés notées</span>}
      </div>

      {data && (
        <div className="layout-2" style={{ marginBottom: '1rem' }}>
          <ScoreHistogram title="Répartition des scores" counts={data.histogram} />
          {universe ? (
            <ProfileScatter universe={universe} profiles={profiles} highlightSector={sector || undefined}
                            y={profile} x={profile === 'VALUE' ? 'QUALITY' : 'VALUE'} />
          ) : <div className="chart-card"><p className="muted">Chargement du nuage de points…</p></div>}
        </div>
      )}

      {isPending && <p className="muted">Chargement…</p>}
      {error && <p className="error">Impossible de charger le classement : {error.message}</p>}

      {data && (
        <>
          {data.totalItems === 0 && <p className="muted">Aucune société notée ne correspond à ces filtres.</p>}
          <div ref={tableTop} className={`table-wrap${isPlaceholderData ? ' is-refreshing' : ''}`}>
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
                {data.items.map((r, i) => (
                  <tr key={r.isin}>
                    <td className="num muted">{data.page * data.size + i + 1}</td>
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
          <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} size={data.size}
                      onChange={goToPage} />
        </>
      )}

      {data && data.unscoredCount > 0 && (
        <section className="unscored">
          <button className="link" onClick={() => setShowUnscored(!showUnscored)}>
            {showUnscored ? '▾' : '▸'} {data.unscoredCount} sociétés non notées (moins de 60 % des indicateurs calculables, ou comptes de plus de 28 mois)
          </button>
          {showUnscored && unscored.data && (
            <>
              <ul className="columns">
                {unscored.data.items.map(r => (
                  <li key={r.isin}><Link to={`/societe/${r.isin}?profil=${profile}`}>{r.name}</Link></li>
                ))}
              </ul>
              <Pagination page={unscored.data.page} totalPages={unscored.data.totalPages}
                          totalItems={unscored.data.totalItems} size={unscored.data.size} onChange={setUnscoredPage} />
            </>
          )}
        </section>
      )}
    </>
  )
}
