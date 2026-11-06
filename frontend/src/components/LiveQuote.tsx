import { useEffect, useRef, useState } from 'react'
import { useQuote } from '../api/queries'
import { Term } from './Term'
import type { LiveStatus } from '../api/live'
import { formatDateTime, formatPrice } from '../format'

/**
 * Le cours, mis à jour en direct : quand un nouveau cours arrive (useLiveQuotes écrit dans le
 * cache), ce composant se réaffiche et le prix « clignote » en vert ou en rouge.
 */
export function LiveQuote({ isin }: { isin: string }) {
  const { data: quote, isPending } = useQuote(isin)
  // useRef : une valeur conservée entre deux affichages, mais dont le changement ne
  // provoque PAS de réaffichage (contrairement à useState). Parfait pour « le cours d'avant ».
  const previous = useRef<number | null>(null)
  const [flash, setFlash] = useState<{ dir: 'up' | 'down'; n: number } | null>(null)

  useEffect(() => {
    if (!quote) return
    if (previous.current !== null && quote.price !== previous.current) {
      const dir = quote.price > previous.current ? 'up' : 'down'
      setFlash(f => ({ dir, n: (f?.n ?? 0) + 1 }))
    }
    previous.current = quote.price
  }, [quote])

  if (!quote) return <div className="quote"><span className="muted">{isPending ? '…' : 'Pas de cours'}</span></div>

  const change = quote.previousClose ? quote.price / quote.previousClose - 1 : null
  return (
    <div className="quote">
      {/* key change à chaque variation : React recrée l'élément, ce qui relance l'animation CSS */}
      <strong key={flash?.n ?? 0} className={flash ? `flash-${flash.dir}` : undefined}>
        {formatPrice(quote.price, quote.currency)}
      </strong>
      {change !== null && (
        <span className={`change ${change >= 0 ? 'delta-up' : 'delta-down'}`}>
          {change >= 0 ? '▲ +' : '▼ '}{(change * 100).toLocaleString('fr-FR', { maximumFractionDigits: 2 })} % <Term id="DAILY_CHANGE">aujourd'hui</Term>
        </span>
      )}
      <span className="muted">
        {quote.source === 'YAHOO' ? <Term id="DELAYED">Cours différé (~15 min)</Term> : <Term id="PREVIOUS_CLOSE">Dernière clôture</Term>} · {formatDateTime(quote.asOf)}
      </span>
    </div>
  )
}

export function LiveBadge({ status }: { status: LiveStatus }) {
  const label = status === 'open' ? 'Direct' : status === 'connecting' ? 'Connexion…' : 'Hors ligne'
  return (
    <span className={`live live-${status}`} title="Cours poussés par le serveur dès qu'ils changent sur Yahoo">
      <span className="live-dot" /> <Term id="LIVE">{label}</Term>
    </span>
  )
}
