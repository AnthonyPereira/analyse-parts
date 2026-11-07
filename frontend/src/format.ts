// Mise en forme à la française : 15,3 %, 1,8x, 80,8 Md€, 06/11/2026.

import type { Freshness, Unit } from './api/types'

const fr = (digits: number) =>
  new Intl.NumberFormat('fr-FR', { minimumFractionDigits: digits, maximumFractionDigits: digits })

/** Valeur d'un ratio selon son unité : 0,153 → « 15,3 % », 1,8 → « 1,8x ». */
export function formatRatio(value: number, unit: Unit): string {
  return unit === 'PERCENT' ? `${fr(1).format(value * 100)} %` : `${fr(1).format(value)}x`
}

/** Montant en millions ou milliards : 80 807 000 000 → « 80,8 Md € ». */
export function formatAmount(value: number, currency: string | null): string {
  const symbol = currency === 'EUR' ? '€' : currency === 'USD' ? '$' : (currency ?? '')
  const abs = Math.abs(value)
  if (abs >= 1e9) return `${fr(1).format(value / 1e9)} Md ${symbol}`
  if (abs >= 1e6) return `${fr(0).format(value / 1e6)} M ${symbol}`
  return `${fr(0).format(value)} ${symbol}`
}

export function formatPrice(value: number, currency: string): string {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency }).format(value)
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('fr-FR')
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' })
}

export function formatPercent(share: number): string {
  return `${fr(0).format(share * 100)} %`
}

export const FRESHNESS_LABEL: Record<Freshness, string> = {
  UP_TO_DATE: 'À jour',
  LATE: 'En retard',
  OBSOLETE: 'Obsolète',
}

export const FRESHNESS_HELP: Record<Freshness, string> = {
  UP_TO_DATE: 'Exercice clos il y a moins de 16 mois : le dernier rapport annuel est connu.',
  LATE: 'Un rapport annuel plus récent devrait exister mais n\'est pas encore importé.',
  OBSOLETE: 'Données trop anciennes (plus de 28 mois) : pas de score.',
}

/** Libellés des postes comptables (enum Java Metric). */
export const METRIC_LABEL: Record<string, string> = {
  REVENUE: 'Chiffre d\'affaires',
  GROSS_PROFIT: 'Marge brute',
  OPERATING_INCOME: 'Résultat opérationnel',
  NET_BANKING_INCOME: 'Produit net bancaire',
  GROSS_OPERATING_INCOME: 'Résultat brut d\'exploitation',
  COST_OF_RISK: 'Coût du risque',
  PROFIT_BEFORE_TAX: 'Résultat avant impôt',
  INCOME_TAX: 'Impôt sur les bénéfices',
  NET_INCOME: 'Résultat net',
  NET_INCOME_GROUP: 'Résultat net part du groupe',
  EPS_BASIC: 'Bénéfice par action',
  EPS_DILUTED: 'Bénéfice par action dilué',
  TOTAL_ASSETS: 'Total du bilan',
  CURRENT_ASSETS: 'Actif courant',
  CURRENT_LIABILITIES: 'Passif courant',
  EQUITY: 'Capitaux propres',
  EQUITY_GROUP: 'Capitaux propres part du groupe',
  CASH: 'Trésorerie',
  INVENTORIES: 'Stocks',
  GOODWILL: 'Écarts d\'acquisition (goodwill)',
  LONG_TERM_DEBT: 'Dettes financières à long terme',
  SHORT_TERM_DEBT: 'Dettes financières à court terme',
  OPERATING_CASH_FLOW: 'Flux de trésorerie d\'exploitation',
  CAPEX: 'Investissements',
  DIVIDENDS_PAID: 'Dividendes versés',
  DIVIDENDS_PER_SHARE: 'Dividende par action versé',
}

/** Code ICB du super-secteur des banques : elles sont notées avec leurs propres indicateurs. */
export const BANKS_SUPERSECTOR = '3010'

/** Les poids d'un profil pour une société : ceux des banques si c'en est une. */
export function weightsFor(profile: { weights: Record<string, number>; bankWeights: Record<string, number> } | undefined,
                           supersectorCode: string | null | undefined): Record<string, number> {
  if (!profile) return {}
  return supersectorCode === BANKS_SUPERSECTOR ? profile.bankWeights : profile.weights
}

/** Couleur d'un score : rouge sous 40, orange jusqu'à 60, vert au-delà. */
export function scoreTone(score: number | null): 'good' | 'mid' | 'bad' | 'none' {
  if (score === null) return 'none'
  if (score >= 60) return 'good'
  if (score >= 40) return 'mid'
  return 'bad'
}
