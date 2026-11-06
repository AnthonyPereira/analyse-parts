import { useFinancials, useRatioHistory, useUniverse } from '../api/queries'
import { Term } from '../components/Term'
import { compactAmount } from './common'
import { YearChart, type YearRow } from './YearChart'

const pct = (v: number) => `${(v * 100).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`
const pctTick = (v: number) => `${(v * 100).toLocaleString('fr-FR', { maximumFractionDigits: 0 })} %`
const mult = (v: number) => `${v.toLocaleString('fr-FR', { maximumFractionDigits: 1 })}x`

/**
 * L'évolution de la société, exercice par exercice : un petit graphique par question
 * (grandit-elle ? est-elle rentable ? endettée ? chère ?). Les ratios de chaque année sont
 * calculés par le serveur comme s'il était à la date de clôture (cours de ce jour-là compris).
 */
export function HistoryCharts({ isin }: { isin: string }) {
  const financials = useFinancials(isin)
  const ratios = useRatioHistory(isin)
  const universe = useUniverse()

  if (financials.isPending || ratios.isPending) return <p className="muted">Chargement…</p>
  const years = [...(financials.data?.years ?? [])].sort((a, b) => a.fiscalYearEnd.localeCompare(b.fiscalYearEnd))
  if (years.length === 0) return <p className="muted">Aucun rapport annuel importé.</p>

  const currency = Object.values(years[years.length - 1].metrics)[0]?.currency ?? 'EUR'
  const symbol = currency === 'EUR' ? '€' : currency === 'USD' ? '$' : currency
  const money = (v: number) => `${compactAmount(v)} ${symbol}`

  const label = (date: string) => {
    const d = new Date(date)
    // Exercice décalé (clôture au 31 mars…) : on montre le mois pour éviter les confusions.
    return d.getMonth() === 11 ? String(d.getFullYear()) : d.toLocaleDateString('fr-FR', { month: 'short', year: 'numeric' })
  }

  // Une ligne par exercice : les montants des comptes + les ratios de cet exercice.
  const rows: YearRow[] = years.map(y => {
    const m = (k: string) => y.metrics[k]?.value ?? null
    const ocf = m('OPERATING_CASH_FLOW')
    const capex = m('CAPEX')
    const r = ratios.data?.find(x => x.fiscalYearEnd === y.fiscalYearEnd)?.values ?? {}
    return {
      year: label(y.fiscalYearEnd),
      REVENUE: m('REVENUE'), OPERATING_INCOME: m('OPERATING_INCOME'), NET_INCOME_GROUP: m('NET_INCOME_GROUP'),
      EPS_BASIC: m('EPS_BASIC'), DIVIDENDS_PAID: m('DIVIDENDS_PAID'),
      OPERATING_CASH_FLOW: ocf, CAPEX: capex, FCF: ocf !== null && capex !== null ? ocf - Math.abs(capex) : null,
      EQUITY_GROUP: m('EQUITY_GROUP'), CASH: m('CASH'),
      ...Object.fromEntries(Object.entries(r)),
    }
  })

  // Pour la valorisation, on ajoute « aujourd'hui » (cours du jour, derniers comptes).
  const now = universe.data?.find(u => u.isin === isin)?.ratios
  const valuationRows: YearRow[] = [
    ...rows.map(r => ({ year: r.year, PER: r.PER, EV_TO_EBIT: r.EV_TO_EBIT, PRICE_TO_BOOK: r.PRICE_TO_BOOK,
      FCF_YIELD: r.FCF_YIELD, DIVIDEND_YIELD: r.DIVIDEND_YIELD })),
    ...(now ? [{ year: 'Auj.', PER: now.PER ?? null, EV_TO_EBIT: now.EV_TO_EBIT ?? null,
      PRICE_TO_BOOK: now.PRICE_TO_BOOK ?? null, FCF_YIELD: now.FCF_YIELD ?? null,
      DIVIDEND_YIELD: now.DIVIDEND_YIELD ?? null }] : []),
  ]

  return (
    <div className="chart-grid">
      <YearChart title="Activité et résultats" help="INCOME_STATEMENT" subtitle="Grandit-elle, et ses bénéfices suivent-ils ?" kind="column"
                 data={rows} format={money} tickFormat={compactAmount} zeroLine
                 series={[{ key: 'REVENUE', label: 'Chiffre d\'affaires' },
                   { key: 'OPERATING_INCOME', label: 'Résultat opérationnel' },
                   { key: 'NET_INCOME_GROUP', label: 'Résultat net' }]} />
      <YearChart title="Marges" subtitle="Sur 100 € de ventes, combien reste-t-il ?" kind="line"
                 data={rows} format={pct} tickFormat={pctTick} zeroLine
                 series={[{ key: 'OPERATING_MARGIN', label: 'Marge opérationnelle' },
                   { key: 'NET_MARGIN', label: 'Marge nette' }, { key: 'FCF_MARGIN', label: 'Marge de cash-flow libre' }]} />
      <YearChart title="Rentabilité du capital" subtitle="Ce que rapportent l'argent des actionnaires (ROE) et l'ensemble de l'actif (ROA)"
                 kind="line" data={rows} format={pct} tickFormat={pctTick} zeroLine
                 series={[{ key: 'ROE', label: 'ROE' }, { key: 'ROA', label: 'ROA' }]} />
      <YearChart title="Flux de trésorerie" help="CASH_FLOW_STATEMENT" subtitle="Le cash produit par le métier, ce qui est réinvesti, ce qui reste"
                 kind="column" data={rows} format={money} tickFormat={compactAmount} zeroLine
                 series={[{ key: 'OPERATING_CASH_FLOW', label: 'Cash d\'exploitation' },
                   { key: 'CAPEX', label: 'Investissements' }, { key: 'FCF', label: 'Cash-flow libre' }]} />
      <YearChart title="Endettement" subtitle={<><Term id="NET_DEBT">Dette nette</Term> ÷ <Term id="EQUITY">capitaux propres</Term> : sous 0, plus de trésorerie que de dettes</>}
                 kind="line" data={rows} format={mult} zeroLine
                 series={[{ key: 'NET_DEBT_TO_EQUITY', label: 'Dette nette / capitaux propres' }]} />
      <YearChart title="Solidité" subtitle={<>Part du <Term id="BALANCE_SHEET">bilan</Term> financée par les actionnaires</>} kind="line"
                 data={rows} format={pct} tickFormat={pctTick}
                 series={[{ key: 'EQUITY_RATIO', label: 'Autonomie financière' }]} />
      <YearChart title="Bénéfice par action" subtitle="Le résultat net rapporté à une action" kind="column"
                 data={rows} format={v => `${v.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} ${symbol}`} zeroLine
                 series={[{ key: 'EPS_BASIC', label: 'Bénéfice par action' }]} />
      <YearChart title="Dividendes versés" subtitle="Montant total versé aux actionnaires dans l'année" kind="column"
                 data={rows} format={money} tickFormat={compactAmount}
                 series={[{ key: 'DIVIDENDS_PAID', label: 'Dividendes versés' }]} />
      <YearChart title="Taux de distribution" subtitle="Part du bénéfice reversée en dividendes" kind="line"
                 data={rows} format={pct} tickFormat={pctTick}
                 series={[{ key: 'PAYOUT_RATIO', label: 'Taux de distribution' }]} />
      <YearChart title="Valorisation (multiples)" subtitle={<>Prix payé pour 1 € de bénéfice, à la clôture de chaque exercice et <Term id="TODAY_POINT">aujourd'hui</Term></>}
                 kind="line" data={valuationRows} format={mult}
                 series={[{ key: 'PER', label: 'PER' }, { key: 'EV_TO_EBIT', label: 'VE / résultat opérationnel' },
                   { key: 'PRICE_TO_BOOK', label: 'Cours / valeur comptable' }]} />
      <YearChart title="Rendements" subtitle={<>Ce que rapporte l'action chaque année, en % de son prix (<Term id="TODAY_POINT">Auj.</Term> : au cours du jour)</>} kind="line"
                 data={valuationRows} format={pct} tickFormat={pctTick}
                 series={[{ key: 'FCF_YIELD', label: 'Rendement du cash-flow libre' },
                   { key: 'DIVIDEND_YIELD', label: 'Rendement du dividende' }]} />
    </div>
  )
}
