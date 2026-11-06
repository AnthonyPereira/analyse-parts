import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router'
import { glossary } from '../glossary'

/**
 * Un mot technique avec sa définition : souligné en pointillés, suivi d'un petit « ? ».
 * Survol (souris), focus (clavier) ou toucher (mobile) ouvre une bulle d'aide ; la bulle
 * renvoie vers la définition complète dans le lexique.
 *
 * La bulle est dessinée à la racine de la page (createPortal) et en position fixe : un
 * tableau qui défile ou une carte aux bords coupés ne peut donc pas la masquer.
 *
 * @param id       identifiant dans le lexique (ROE, SCORE…)
 * @param children le texte affiché ; par défaut le nom du terme dans le lexique
 */
export function Term({ id, children, fallback }: { id: string; children?: ReactNode; fallback?: string }) {
  const entry = glossary(id)
  const [open, setOpen] = useState(false)
  const [pinned, setPinned] = useState(false) // ouverte par un clic : reste ouverte au départ de la souris
  const anchor = useRef<HTMLButtonElement>(null)
  const bubble = useRef<HTMLDivElement>(null)
  const closeTimer = useRef<number | undefined>(undefined)
  const [pos, setPos] = useState<{ top: number; left: number; above: boolean } | null>(null)
  const bubbleId = useId()

  const show = () => { window.clearTimeout(closeTimer.current); setOpen(true) }
  // Petit délai : la souris peut passer du mot à la bulle sans la fermer.
  const hideSoon = () => { if (!pinned) closeTimer.current = window.setTimeout(() => setOpen(false), 150) }
  const close = () => { setOpen(false); setPinned(false) }

  // Placer la bulle sous le mot (ou au-dessus s'il n'y a pas la place), sans sortir de l'écran.
  useLayoutEffect(() => {
    if (!open || !anchor.current) return
    const place = () => {
      const r = anchor.current!.getBoundingClientRect()
      const width = Math.min(340, window.innerWidth - 16)
      const height = bubble.current?.offsetHeight ?? 160
      const above = r.bottom + height + 12 > window.innerHeight && r.top > height + 12
      setPos({
        top: above ? r.top - height - 6 : r.bottom + 6,
        left: Math.max(8, Math.min(r.left, window.innerWidth - width - 8)),
        above,
      })
    }
    place()
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [open])

  // Échap ou un clic ailleurs ferment la bulle.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { close(); anchor.current?.focus() } }
    const onClick = (e: MouseEvent) => {
      const t = e.target as Node
      if (!anchor.current?.contains(t) && !bubble.current?.contains(t)) close()
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onClick)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onClick)
    }
  }, [open])

  const label = children ?? entry?.term ?? fallback ?? id
  if (!entry) return <>{label}</>

  return (
    <>
      <button
        ref={anchor} type="button" className="term" aria-expanded={open} aria-describedby={open ? bubbleId : undefined}
        onMouseEnter={show} onMouseLeave={hideSoon} onFocus={show} onBlur={hideSoon}
        onClick={e => {
          e.preventDefault() // le mot peut être dans un <summary> ou un lien : ne pas les déclencher
          e.stopPropagation()
          if (pinned) close(); else { setPinned(true); show() }
        }}>
        {label}<span className="term-mark" aria-hidden>?</span>
      </button>
      {open && createPortal(
        <div ref={bubble} id={bubbleId} role="tooltip" className="term-bubble"
             style={{ top: pos?.top ?? -9999, left: pos?.left ?? -9999 }}
             onMouseEnter={show} onMouseLeave={hideSoon}>
          <p className="term-title">{entry.term}</p>
          <p>{entry.short}</p>
          {entry.formula && <p className="term-formula"><span>Calcul :</span> {entry.formula}</p>}
          {entry.read && <p className="term-read">{entry.read}</p>}
          <Link to={`/lexique#${id}`} onClick={close}>Tout comprendre dans le lexique →</Link>
        </div>,
        document.body,
      )}
    </>
  )
}

/** Un encadré repliable « Comment lire cette page ? » en haut d'une page. */
export function HowToRead({ title = 'Comment lire cette page ?', children }: { title?: string; children: ReactNode }) {
  return (
    <details className="howto">
      <summary>{title}</summary>
      <div className="howto-body">{children}</div>
    </details>
  )
}
