// Les types TypeScript des réponses de l'API Spring Boot.
// Ils reprennent un à un les records Java (ScoringController, CompanyController…) :
// si un record change côté Java, c'est ici qu'il faut le répercuter.

export type ProfileId = 'QUALITY' | 'GROWTH' | 'DIVIDEND' | 'VALUE'
export type Freshness = 'UP_TO_DATE' | 'LATE' | 'OBSOLETE'
export type Unit = 'PERCENT' | 'MULTIPLE'

export interface Icb {
  code: string
  industry: string
  supersector: string
  sector: string
  subsector: string
}

export interface CompanySummary {
  isin: string
  name: string
  ticker: string
  supersector: string | null
}

export interface Company {
  isin: string
  lei: string | null
  name: string
  legalName: string | null
  ticker: string
  country: string | null
  market: string
  mic: string
  icb: Icb | null
  sharesAdmitted: number | null
}

export interface Quote {
  price: number
  currency: string
  asOf: string // ISO 8601
  source: 'YAHOO' | 'EURONEXT_CLOSE'
  previousClose?: number | null
}

export interface RatioDefinition {
  id: string
  family: string
  familyLabel: string
  label: string
  description: string
  unit: Unit
  sectorAdjusted: boolean
  scale: { value: number; points: number }[]
}

export interface ProfileDefinition {
  id: ProfileId
  label: string
  description: string
  weights: Record<string, number>
  /** Poids appliqués aux banques (super-secteur ICB 3010) : indicateurs du métier bancaire. */
  bankWeights: Record<string, number>
}

export interface RankingEntry {
  isin: string
  name: string
  ticker: string
  sector: string | null
  score: number | null
  coverage: number
  fiscalYearEnd: string | null
  freshness: Freshness | null
  familyScore: Record<string, number>
}

export interface SectorAdjustment {
  level: 'SUPERSECTOR' | 'INDUSTRY'
  code: string
  label: string
  factor: number
  sectorMedian: number
  universeMedian: number
  sampleSize: number
}

export interface Input {
  item: string
  date: string
  value: number
  currency: string | null
  source: string
}

export interface Contribution {
  ratio: string
  value: number
  points: number
  weight: number
  share: number
  contribution: number
  sectorAdjustment: SectorAdjustment | null
  inputs: Input[]
}

export interface Missing {
  ratio: string
  weight: number
  reason: string
}

export interface Score {
  score: number | null
  coverage: number
  fiscalYearEnd: string | null
  freshness: Freshness | null
  familyScore: Record<string, number>
  contributions: Contribution[]
  missing: Missing[]
}

export interface CompanyScore {
  isin: string
  name: string
  icb: Icb | null
  profile: ProfileId
  score: Score
}

export interface FinancialValue {
  value: number
  currency: string | null
  concept: string
  filing: string
}

export interface FinancialHistory {
  years: { fiscalYearEnd: string; metrics: Record<string, FinancialValue> }[]
}

export interface QuoteWithChange extends Quote {
  previousClose: number | null
}

/** Message reçu en direct par /api/quotes/stream. */
export interface QuoteEvent extends QuoteWithChange {
  isin: string
}

export interface DailyClose {
  day: string
  close: number
  volume: number | null
}

export interface Intraday {
  currency: string
  previousClose: number | null
  points: { time: string; price: number; volume: number | null }[]
}

export type PriceRange = 'D1' | 'M1' | 'M6' | 'Y1' | 'Y5' | 'MAX'

export interface YearRatios {
  fiscalYearEnd: string
  price: number | null
  values: Record<string, number>
}

export interface UniverseEntry {
  isin: string
  name: string
  ticker: string
  supersectorCode: string | null
  supersector: string | null
  freshness: Freshness | null
  ratios: Record<string, number>
  scores: Record<ProfileId, number | null>
}

export interface Median {
  level: 'ALL' | 'SUPERSECTOR' | 'INDUSTRY'
  code: string
  label: string
  ratio: string
  median: number
  sampleSize: number
}

export interface Calibration {
  calibratedAt: string | null
  medians: Median[]
}

/** D'où vient une note enregistrée (voir le lexique : SCORE_ORIGIN). */
export type ScoreOrigin = 'FILING' | 'DAILY' | 'BACKFILL'

/** Un point de l'historique des notes : les notes des 4 profils un jour donné. */
export interface ScorePoint {
  day: string
  origin: ScoreOrigin
  fiscalYearEnd: string | null
  sourceId: string | null
  scores: Partial<Record<ProfileId, number>>
}

/** L'état d'une source externe (Yahoo, BCE, AMF, Euronext, GLEIF), tenu à jour par le backend. */
export type SourceState = 'UNKNOWN' | 'UP' | 'DEGRADED' | 'DOWN'

export interface SourceStatus {
  source: string
  label: string
  state: SourceState
  detail: string | null
  problem: string | null
  lastCheck: string | null
  lastSuccess: string | null
  problemSince: string | null
}
