/**
 * Les boutons « précédente / suivante » sous un tableau découpé en pages. Les numéros de page
 * commencent à 0 dans le code (comme l'API) et à 1 à l'écran.
 */
export function Pagination({ page, totalPages, totalItems, size, onChange }: {
  page: number
  totalPages: number
  totalItems: number
  size: number
  onChange: (page: number) => void
}) {
  if (totalPages <= 1) return null
  const from = page * size + 1
  const to = Math.min((page + 1) * size, totalItems)
  return (
    <nav className="pager" aria-label="Pages du classement">
      <button onClick={() => onChange(0)} disabled={page === 0} aria-label="Première page">«</button>
      <button onClick={() => onChange(page - 1)} disabled={page === 0}>‹ Précédente</button>
      <span className="muted">
        {from}–{to} sur {totalItems} · page {page + 1} / {totalPages}
      </span>
      <button onClick={() => onChange(page + 1)} disabled={page >= totalPages - 1}>Suivante ›</button>
      <button onClick={() => onChange(totalPages - 1)} disabled={page >= totalPages - 1} aria-label="Dernière page">»</button>
    </nav>
  )
}
