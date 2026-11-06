// Petits composants d'affichage. Un composant React est une fonction qui reçoit des
// « props » (ses paramètres, en lecture seule) et renvoie du JSX (le HTML à afficher).

import { Term } from './Term'
import type { Freshness } from '../api/types'
import { FRESHNESS_HELP, FRESHNESS_LABEL, scoreTone } from '../format'

/** Le score sur 100, coloré selon sa valeur ; « — » si la société n'est pas notée. */
export function ScoreBadge({ score, size = 'small' }: { score: number | null; size?: 'small' | 'large' }) {
  return (
    <span className={`score score-${scoreTone(score)} score-${size}`}
          title={score === null ? 'Pas assez de données pour noter' : `${score} sur 100`}>
      {score ?? '—'}
    </span>
  )
}

/** Badge de fraîcheur ; {@code explained} : avec sa bulle d'aide (fiche société). */
export function FreshnessBadge({ freshness, explained = false }: { freshness: Freshness | null; explained?: boolean }) {
  if (freshness === null) return null
  const badge = (
    <span className={`badge badge-${freshness.toLowerCase()}`} title={FRESHNESS_HELP[freshness]}>
      {FRESHNESS_LABEL[freshness]}
    </span>
  )
  return explained ? <Term id={freshness}>{badge}</Term> : badge
}

/** Une barre horizontale de 0 à 100. */
export function PointsBar({ points }: { points: number }) {
  return (
    <span className="bar" aria-hidden>
      <span className={`bar-fill bar-${scoreTone(points)}`} style={{ width: `${Math.max(points, 2)}%` }} />
    </span>
  )
}
