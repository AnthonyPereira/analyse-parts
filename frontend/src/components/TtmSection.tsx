import { useHalfYear } from '../api/queries'
import type { HalfYearItem, Ttm } from '../api/types'
import { formatAmount, formatDate } from '../format'
import { Term } from './Term'

const LABELS: Record<HalfYearItem, { label: string; term: string }> = {
  REVENUE: { label: "Chiffre d'affaires", term: 'REVENUE' },
  OPERATING_INCOME: { label: 'Résultat opérationnel', term: 'OPERATING_INCOME' },
  NET_INCOME_GROUP: { label: 'Résultat net part du groupe', term: 'NET_INCOME_GROUP' },
  EPS_BASIC: { label: 'Bénéfice par action', term: 'EPS_BASIC' },
}

/** Un BPA en euros par action, les autres en millions ou milliards. */
function amount(item: HalfYearItem, value: number): string {
  return item === 'EPS_BASIC'
    ? `${value.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} €`
    : formatAmount(value, 'EUR')
}

/**
 * Les chiffres des 12 derniers mois, tirés du rapport semestriel (lu par le LLM local, vérifié par
 * le backend). Affichés pour information : le score reste calculé sur les comptes annuels (ADR 0015).
 */
export function TtmSection({ isin }: { isin: string }) {
  const { data } = useHalfYear(isin)
  if (!data || !data.report || data.ttm.length === 0) return null
  const report = data.report
  const first = data.ttm[0]
  return (
    <section className="card" style={{ marginTop: '1rem' }}>
      <h2>Sur <Term id="TTM">12 mois glissants</Term></h2>
      <p className="muted small">
        Du {formatDate(start(first.periodEnd))} au {formatDate(first.periodEnd)} : l'exercice clos le{' '}
        {formatDate(first.fiscalYearEnd)}, plus le semestre publié depuis dans le{' '}
        <a href={report.sourceUrl} target="_blank" rel="noreferrer"><Term id="HALF_YEAR_REPORT">rapport semestriel</Term></a>,
        moins le même semestre de l'année précédente. Chiffres recopiés par une IA locale puis vérifiés ;
        ils n'entrent pas dans le score.
      </p>
      <div className="table-wrap">
        <table className="financials">
          <thead>
            <tr>
              <th scope="col">Indicateur</th>
              <th scope="col" className="num">Exercice {new Date(first.fiscalYearEnd).getFullYear()}</th>
              <th scope="col" className="num">+ semestre {new Date(first.periodEnd).getFullYear()}</th>
              <th scope="col" className="num">− semestre {new Date(first.periodEnd).getFullYear() - 1}</th>
              <th scope="col" className="num">= 12 mois</th>
            </tr>
          </thead>
          <tbody>
            {data.ttm.map(t => <Row key={t.item} ttm={t} />)}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function Row({ ttm }: { ttm: Ttm }) {
  const { label, term } = LABELS[ttm.item]
  const change = ttm.fullYear !== 0 ? ttm.value / ttm.fullYear - 1 : null
  return (
    <tr>
      <th scope="row"><Term id={term}>{label}</Term></th>
      <td className="num">{amount(ttm.item, ttm.fullYear)}</td>
      <td className="num">{amount(ttm.item, ttm.current)}</td>
      <td className="num">{amount(ttm.item, ttm.previous)}</td>
      <td className="num">
        <strong>{amount(ttm.item, ttm.value)}</strong>
        {change !== null && ttm.fullYear > 0 && (
          <span className="muted small">
            {' '}({change >= 0 ? '+' : ''}{(change * 100).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %)
          </span>
        )}
      </td>
    </tr>
  )
}

/** Le premier jour des 12 mois : un an plus tôt, le lendemain (30/06/2026 → 01/07/2025). */
function start(iso: string): string {
  const d = new Date(iso)
  d.setFullYear(d.getFullYear() - 1)
  d.setDate(d.getDate() + 1)
  return d.toISOString().slice(0, 10)
}
