import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { useCompanies } from '../api/queries'

/** Recherche d'une société par nom, mnémonique ou ISIN, dans l'en-tête. */
export function CompanySearch() {
  const { data: companies } = useCompanies()
  // useState : une valeur que React garde entre deux affichages. La modifier
  // (setQuery) réaffiche le composant avec la nouvelle valeur.
  const [query, setQuery] = useState('')
  const navigate = useNavigate()

  // useMemo : ne refait le filtrage que si la liste ou la saisie changent.
  const matches = useMemo(() => {
    const q = normalize(query)
    if (q.length < 2 || !companies) return []
    return companies
      .filter(c => normalize(c.name).includes(q) || normalize(c.ticker).startsWith(q) || normalize(c.isin) === q)
      .slice(0, 8)
  }, [companies, query])

  const open = (isin: string) => {
    setQuery('')
    navigate(`/societe/${isin}`)
  }

  return (
    <div className="search">
      <input type="search" placeholder="Rechercher une société…" value={query} aria-label="Rechercher une société"
             onChange={e => setQuery(e.target.value)}
             onKeyDown={e => { if (e.key === 'Enter' && matches.length > 0) open(matches[0].isin) }} />
      {matches.length > 0 && (
        <ul className="search-results">
          {matches.map(c => (
            <li key={c.isin}>
              <button onClick={() => open(c.isin)}>
                <span>{c.name}</span>
                <span className="muted">{c.ticker} · {c.supersector ?? 'secteur inconnu'}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Sans accents ni majuscules : « hermes » trouve « HERMÈS ». */
function normalize(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim()
}
