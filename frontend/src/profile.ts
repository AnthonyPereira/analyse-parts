import type { ProfileId } from './api/types'

const PROFILE_IDS: ProfileId[] = ['QUALITY', 'GROWTH', 'DIVIDEND', 'VALUE']

/** Lit le profil depuis l'URL, avec Qualité par défaut. */
export function parseProfile(raw: string | null): ProfileId {
  return PROFILE_IDS.includes(raw as ProfileId) ? (raw as ProfileId) : 'QUALITY'
}
