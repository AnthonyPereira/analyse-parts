import { useEffect, useRef, useState } from 'react'

/** La largeur réelle d'un élément, mise à jour quand la fenêtre change de taille. */
export function useWidth<T extends HTMLElement>(initial = 600) {
  const ref = useRef<T>(null)
  const [width, setWidth] = useState(initial)
  useEffect(() => {
    if (!ref.current) return
    const observer = new ResizeObserver(entries => setWidth(Math.max(120, entries[0].contentRect.width)))
    observer.observe(ref.current)
    return () => observer.disconnect()
  }, [])
  return [ref, width] as const
}
