import { Term } from './Term'
import type { ProfileId } from '../api/types'
import { useProfiles } from '../api/queries'

/**
 * Choix du profil (Qualité, Croissance, Dividende, Value).
 * Composant « contrôlé » : il ne garde pas le profil choisi lui-même, son parent le lui donne
 * (value) et est prévenu quand il change (onChange). Le parent le range dans l'URL (?profil=),
 * pour qu'un lien partagé ouvre le même profil.
 */
export function ProfileTabs({ value, onChange }: { value: ProfileId; onChange: (p: ProfileId) => void }) {
  const { data: profiles } = useProfiles()
  const current = profiles?.find(p => p.id === value)
  return (
    <div className="profiles">
      <div className="tabs" role="tablist">
        {profiles?.map(p => (
          <button key={p.id} role="tab" aria-selected={p.id === value}
                  className={p.id === value ? 'tab tab-active' : 'tab'} onClick={() => onChange(p.id)}>
            {p.label}
          </button>
        ))}
      </div>
      {current && (
        <p className="muted">
          <Term id={current.id}>{current.label}</Term> : {current.description.charAt(0).toLowerCase() + current.description.slice(1)}{' '}
          <span className="small">(<Term id="PROFILE">qu'est-ce qu'un profil ?</Term>)</span>
        </p>
      )}
    </div>
  )
}

const PROFILE_IDS: ProfileId[] = ['QUALITY', 'GROWTH', 'DIVIDEND', 'VALUE']

/** Lit le profil depuis l'URL, avec Qualité par défaut. */
export function parseProfile(raw: string | null): ProfileId {
  return PROFILE_IDS.includes(raw as ProfileId) ? (raw as ProfileId) : 'QUALITY'
}
