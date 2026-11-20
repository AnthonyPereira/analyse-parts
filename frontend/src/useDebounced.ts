import { useEffect, useState } from 'react'

/** Attend que la valeur arrête de changer (pendant delay ms) avant de la renvoyer. */
export function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer) // une nouvelle valeur annule l'attente précédente
  }, [value, delay])
  return debounced
}
