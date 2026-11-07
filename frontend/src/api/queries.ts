// Accès à l'API avec TanStack Query (« React Query »).
//
// Pourquoi une librairie plutôt qu'un fetch dans un useEffect ?
//  - mise en cache : revenir sur une page déjà vue est instantané ;
//  - états chargement / erreur fournis (isPending, error) ;
//  - pas de requête en double si deux composants demandent la même donnée.
// Chaque donnée est identifiée par une « clé » (queryKey) : même clé = même cache.

import { useQuery } from '@tanstack/react-query'
import type {
  Calibration, Company, CompanyScore, CompanySummary, DailyClose, FinancialHistory, Intraday, PriceRange,
  ProfileDefinition, ProfileId, QuoteWithChange, UniverseEntry, YearRatios,
  RankingEntry, RatioDefinition, ScorePoint,
} from './types'

/** Erreur HTTP, pour distinguer « introuvable » (404) d'une panne. */
export class HttpError extends Error {
  readonly status: number
  constructor(status: number, url: string) {
    super(`${status} sur ${url}`)
    this.status = status
  }
}

// Les URL sont relatives (/api/...) : en développement, Vite les relaie vers Spring Boot
// (voir « proxy » dans vite.config.ts), donc pas besoin de configurer CORS.
async function get<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) {
    throw new HttpError(response.status, url)
  }
  return response.json() as Promise<T>
}

/** Une donnée absente (404) n'est pas une erreur : la fiche affiche simplement « pas de cours ». */
async function getOptional<T>(url: string): Promise<T | null> {
  try {
    return await get<T>(url)
  } catch (e) {
    if (e instanceof HttpError && e.status === 404) return null
    throw e
  }
}

const HOUR = 60 * 60 * 1000

// Les définitions (ratios, profils) ne changent qu'avec le code : on les garde une heure.
export const useRatioDefinitions = () =>
  useQuery({ queryKey: ['ratios'], queryFn: () => get<RatioDefinition[]>('/api/scoring/ratios'), staleTime: HOUR })

export const useProfiles = () =>
  useQuery({ queryKey: ['profiles'], queryFn: () => get<ProfileDefinition[]>('/api/scoring/profiles'), staleTime: HOUR })

export const useCompanies = () =>
  useQuery({ queryKey: ['companies'], queryFn: () => get<CompanySummary[]>('/api/companies'), staleTime: HOUR })

export const useRanking = (profile: ProfileId) =>
  useQuery({ queryKey: ['ranking', profile], queryFn: () => get<RankingEntry[]>(`/api/scores?profile=${profile}`) })

export const useCompany = (isin: string) =>
  useQuery({ queryKey: ['company', isin], queryFn: () => get<Company>(`/api/companies/${isin}`) })

export const useCompanyScore = (isin: string, profile: ProfileId) =>
  useQuery({
    queryKey: ['score', isin, profile],
    queryFn: () => get<CompanyScore>(`/api/companies/${isin}/score?profile=${profile}`),
  })

export const useFinancials = (isin: string) =>
  useQuery({ queryKey: ['financials', isin], queryFn: () => get<FinancialHistory>(`/api/companies/${isin}/financials`) })

// Le cours change toutes les 15 minutes en séance : on le relit toutes les 5 minutes.
export const useQuote = (isin: string) =>
  useQuery({
    queryKey: ['quote', isin],
    queryFn: () => getOptional<QuoteWithChange>(`/api/companies/${isin}/quote`),
    refetchInterval: 5 * 60 * 1000,
  })

// ---- Graphiques ----

export const usePrices = (isin: string, range: Exclude<PriceRange, 'D1'>, enabled = true) =>
  useQuery({
    queryKey: ['prices', isin, range],
    queryFn: () => get<DailyClose[]>(`/api/companies/${isin}/prices?range=${range}`),
    staleTime: HOUR,
    enabled,
  })

// La séance du jour : complétée en direct par useLiveQuotes (live.ts), relue toutes les 5 minutes.
export const useIntraday = (isin: string, enabled = true) =>
  useQuery({
    queryKey: ['intraday', isin],
    queryFn: () => getOptional<Intraday>(`/api/companies/${isin}/prices/intraday`),
    refetchInterval: 5 * 60 * 1000,
    enabled,
  })

// Historique des notes : à chaque rapport, chaque soir de bourse, et reconstitué pour le passé.
export const useScoreHistory = (isin: string) =>
  useQuery({ queryKey: ['scoreHistory', isin], queryFn: () => get<ScorePoint[]>(`/api/companies/${isin}/score-history`) })

export const useRatioHistory = (isin: string) =>
  useQuery({ queryKey: ['ratioHistory', isin], queryFn: () => get<YearRatios[]>(`/api/companies/${isin}/ratios/history`) })

// Toutes les sociétés avec leurs ratios et scores (~2 s à calculer côté serveur) : gardé 5 minutes.
export const useUniverse = () =>
  useQuery({ queryKey: ['universe'], queryFn: () => get<UniverseEntry[]>('/api/universe'), staleTime: 5 * 60 * 1000 })

export const useCalibration = () =>
  useQuery({ queryKey: ['calibration'], queryFn: () => get<Calibration>('/api/scoring/calibration'), staleTime: HOUR })

/** Mode avancé : les poids font partie de la clé, donc chaque réglage a son propre cache. */
export const useCustomRanking = (weights: Record<string, number>) =>
  useQuery({
    queryKey: ['customRanking', weights],
    queryFn: async () => {
      const response = await fetch('/api/scores/custom', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(weights),
      })
      if (!response.ok) throw new HttpError(response.status, '/api/scores/custom')
      return response.json() as Promise<RankingEntry[]>
    },
    enabled: Object.values(weights).some(w => w > 0),
    placeholderData: previous => previous, // garde l'ancien classement affiché pendant le calcul
  })
