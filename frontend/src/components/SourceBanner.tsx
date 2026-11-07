import { useSources } from '../api/queries'
import { formatDateTime } from '../format'
import { Term } from './Term'

/**
 * Un bandeau en haut de chaque page quand une source de données est en panne : sans lui, on verrait
 * des cours figés ou des rapports manquants sans savoir pourquoi. Rien ne s'affiche si tout va bien.
 * La couleur ne porte jamais seule l'information : chaque ligne dit « en panne » ou « partiellement ».
 */
export function SourceBanner() {
  const { data } = useSources()
  const problems = (data ?? []).filter(s => s.state === 'DOWN' || s.state === 'DEGRADED')
  if (problems.length === 0) return null
  const severe = problems.some(s => s.state === 'DOWN')
  return (
    <div className={`notice source-banner ${severe ? 'notice-bad' : 'notice-warn'}`} role="status">
      <strong><span aria-hidden>⚠ </span>
        {problems.length === 1 ? 'Une source de données a un problème' : 'Des sources de données ont un problème'}
      </strong>{' '}
      (<Term id="SOURCE_STATUS">qu'est-ce que ça change ?</Term>)
      <ul>
        {problems.map(s => (
          <li key={s.source}>
            <strong>{s.label}</strong> : {s.state === 'DOWN' ? 'en panne' : 'partiellement en échec'}
            {s.problemSince && <> depuis le {formatDateTime(s.problemSince)}</>}. {s.problem}
            {s.lastSuccess && <span className="muted"> Dernière collecte réussie : {formatDateTime(s.lastSuccess)}.</span>}
          </li>
        ))}
      </ul>
    </div>
  )
}
