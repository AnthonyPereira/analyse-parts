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
