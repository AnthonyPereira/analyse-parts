import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { CATEGORIES, GLOSSARY, type Entry } from '../glossary'

/** Sans accents et en minuscules : « écart » se trouve en tapant « ecart ». */
const normalize = (s: string) => s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()

/** Le lexique : chaque terme de l'application, rangé par thème, avec une recherche. */
export function GlossaryPage() {
  const [query, setQuery] = useState('')
  const { hash } = useLocation()
  const target = decodeURIComponent(hash.replace('#', ''))

  // Arrivée depuis une bulle d'aide (/lexique#ROE) : on fait défiler jusqu'au terme.
  useEffect(() => {
    if (!target) return
    document.getElementById(target)?.scrollIntoView({ block: 'start' })
  }, [target])

  const entries = useMemo(() => Object.entries(GLOSSARY), [])
  const q = normalize(query.trim())
  const matches = (e: Entry) => !q || [e.term, e.short, e.what ?? ''].some(t => normalize(t).includes(q))

  return (
    <>
      <h1>Lexique</h1>
      <p className="muted">
        Tous les mots de l'application, expliqués simplement. Sur chaque page, un mot souligné en pointillés
        suivi d'un <span className="term-mark" aria-hidden>?</span> renvoie ici : survolez-le ou touchez-le pour sa définition.
      </p>
      <input className="glossary-search" type="search" placeholder="Chercher un terme : PER, dette, médiane…"
             value={query} onChange={e => setQuery(e.target.value)} aria-label="Chercher dans le lexique" />

      <nav className="glossary-toc" aria-label="Thèmes">
        {CATEGORIES.map(c => <a key={c.id} href={`#cat-${c.id}`}>{c.id}</a>)}
      </nav>

      {CATEGORIES.map(category => {
        const list = entries.filter(([, e]) => e.category === category.id && matches(e))
        if (list.length === 0) return null
        return (
          <section key={category.id} className="glossary-section">
            <h2 id={`cat-${category.id}`}>{category.id}</h2>
            <p className="muted">{category.intro}</p>
            {list.map(([id, e]) => <GlossaryEntry key={id} id={id} entry={e} highlighted={id === target} />)}
          </section>
        )
      })}
      {entries.every(([, e]) => !matches(e)) && <p className="muted">Aucun terme ne correspond à « {query} ».</p>}
    </>
  )
}

function GlossaryEntry({ id, entry, highlighted }: { id: string; entry: Entry; highlighted: boolean }) {
  return (
    <article id={id} className={highlighted ? 'glossary-entry glossary-target' : 'glossary-entry'}>
      <h3>{entry.term}</h3>
      <p className="glossary-short">{entry.short}</p>
      {entry.what && <p>{entry.what}</p>}
      <dl>
        {entry.formula && <><dt>Calcul</dt><dd className="term-formula">{entry.formula}</dd></>}
        {entry.read && <><dt>Comment le lire</dt><dd>{entry.read}</dd></>}
        {entry.example && <><dt>Exemple</dt><dd>{entry.example}</dd></>}
        {entry.watch && <><dt>Attention</dt><dd>{entry.watch}</dd></>}
      </dl>
      {entry.see && (
        <p className="small muted">
          Voir aussi :{' '}
          {entry.see.map((s, i) => (
            <span key={s}>{i > 0 && ', '}<Link to={`#${s}`}>{GLOSSARY[s]?.term ?? s}</Link></span>
          ))}
        </p>
      )}
    </article>
  )
}
