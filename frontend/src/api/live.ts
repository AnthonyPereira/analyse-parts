// Le direct : une connexion « Server-Sent Events » ouverte sur /api/quotes/stream.
//
// À chaque message, on écrit le nouveau cours directement dans le cache de TanStack Query.
// Tous les composants qui affichent ce cours (useQuote) se réaffichent tout seuls : c'est
// tout l'intérêt d'un cache partagé. La courbe du jour reçoit aussi le nouveau point.

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { Intraday, QuoteEvent, QuoteWithChange } from './types'

export type LiveStatus = 'connecting' | 'open' | 'closed'

/** À appeler une seule fois, en haut de l'application. Renvoie l'état de la connexion. */
export function useLiveQuotes(): LiveStatus {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<LiveStatus>('connecting')

  // useEffect : du code qui s'exécute APRÈS l'affichage, pour se brancher sur le monde
  // extérieur (ici une connexion réseau). La fonction renvoyée est appelée au démontage :
  // on y ferme la connexion, sinon elle resterait ouverte.
  useEffect(() => {
    const source = new EventSource('/api/quotes/stream')
    source.onopen = () => setStatus('open')
    // EventSource se reconnecte tout seul ; on affiche seulement l'état.
    source.onerror = () => setStatus(source.readyState === EventSource.CLOSED ? 'closed' : 'connecting')

    source.addEventListener('quotes', (event: MessageEvent<string>) => {
      const quotes = JSON.parse(event.data) as QuoteEvent[]
      for (const { isin, ...quote } of quotes) {
        queryClient.setQueryData<QuoteWithChange | null>(['quote', isin], quote)
        queryClient.setQueryData<Intraday | null>(['intraday', isin], old => appendPoint(old, quote))
      }
    })
    return () => source.close()
  }, [queryClient])

  return status
}

/** Ajoute le cours à la courbe du jour s'il est plus récent que son dernier point (même séance). */
function appendPoint(old: Intraday | null | undefined, quote: QuoteWithChange): Intraday | null | undefined {
  if (!old || old.points.length === 0) return old
  const last = old.points[old.points.length - 1]
  const sameDay = new Date(last.time).toDateString() === new Date(quote.asOf).toDateString()
  if (!sameDay || quote.asOf <= last.time) return old
  // On renvoie un NOUVEL objet : React compare les références pour savoir s'il faut réafficher.
  return { ...old, points: [...old.points, { time: quote.asOf, price: quote.price, volume: null }] }
}
