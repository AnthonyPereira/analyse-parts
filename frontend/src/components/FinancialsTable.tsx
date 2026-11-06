import { Term } from './Term'
import { useFinancials } from '../api/queries'
import { formatAmount, formatDate, METRIC_LABEL } from '../format'

// L'ordre de lecture d'un rapport annuel : compte de résultat, bilan, flux de trésorerie.
const ROWS: { title: string; term: string; metrics: string[] }[] = [
  { title: 'Compte de résultat', term: 'INCOME_STATEMENT', metrics: ['REVENUE', 'OPERATING_INCOME', 'NET_INCOME_GROUP', 'EPS_BASIC'] },
  { title: 'Bilan', term: 'BALANCE_SHEET', metrics: ['TOTAL_ASSETS', 'EQUITY_GROUP', 'CASH', 'LONG_TERM_DEBT', 'SHORT_TERM_DEBT'] },
  { title: 'Flux de trésorerie', term: 'CASH_FLOW_STATEMENT', metrics: ['OPERATING_CASH_FLOW', 'CAPEX', 'DIVIDENDS_PAID'] },
]

/** Les principaux postes des comptes, un exercice par colonne (le plus récent à gauche). */
export function FinancialsTable({ isin }: { isin: string }) {
  const { data, isPending, error } = useFinancials(isin)
  if (isPending) return <p className="muted">Chargement…</p>
  if (error || !data || data.years.length === 0) return <p className="muted">Aucun rapport annuel importé.</p>

  const years = [...data.years].sort((a, b) => b.fiscalYearEnd.localeCompare(a.fiscalYearEnd))

  return (
    <div className="table-wrap">
      <table className="financials">
        <thead>
          <tr>
            <th />
            {years.map(y => <th key={y.fiscalYearEnd} className="num">{formatDate(y.fiscalYearEnd)}</th>)}
          </tr>
        </thead>
        {ROWS.map(group => (
          <tbody key={group.title}>
            <tr className="group"><th colSpan={years.length + 1}><Term id={group.term}>{group.title}</Term></th></tr>
            {group.metrics.map(m => (
              <tr key={m}>
                <td><Term id={m}>{METRIC_LABEL[m] ?? m}</Term></td>
                {years.map(y => {
                  const v = y.metrics[m]
                  return (
                    <td key={y.fiscalYearEnd} className="num" title={v?.concept}>
                      {v ? (m === 'EPS_BASIC' ? `${v.value.toLocaleString('fr-FR')} ${v.currency === 'EUR' ? '€' : v.currency ?? ''}`
                        : formatAmount(v.value, v.currency)) : '—'}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        ))}
      </table>
    </div>
  )
}
