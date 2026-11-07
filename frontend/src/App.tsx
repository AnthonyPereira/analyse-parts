import { lazy, Suspense } from 'react'
import { Link, NavLink, Route, Routes } from 'react-router'
import { useLiveQuotes } from './api/live'
import { CompanySearch } from './components/CompanySearch'
import { LiveBadge } from './components/LiveQuote'
import { SourceBanner } from './components/SourceBanner'
// Chaque page est un fichier JavaScript à part, téléchargé seulement quand on l'ouvre
// (« code splitting ») : la première page s'affiche sans attendre le code des autres.
const RankingPage = lazy(() => import('./pages/RankingPage').then(m => ({ default: m.RankingPage })))
const CompanyPage = lazy(() => import('./pages/CompanyPage').then(m => ({ default: m.CompanyPage })))
const SectorsPage = lazy(() => import('./pages/SectorsPage').then(m => ({ default: m.SectorsPage })))
const SectorPage = lazy(() => import('./pages/SectorsPage').then(m => ({ default: m.SectorPage })))
const AdvancedPage = lazy(() => import('./pages/AdvancedPage').then(m => ({ default: m.AdvancedPage })))
const GlossaryPage = lazy(() => import('./pages/GlossaryPage').then(m => ({ default: m.GlossaryPage })))

/**
 * La mise en page commune (en-tête, pied de page) et les routes : l'URL choisit la page
 * affichée au milieu, sans recharger le navigateur (« Single Page Application »).
 */
export function App() {
  // Une seule connexion de direct pour toute l'application, quelle que soit la page.
  const live = useLiveQuotes()
  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand">Analyse Parts</Link>
        <nav className="mainnav">
          {/* NavLink ajoute la classe « active » au lien de la page affichée */}
          <NavLink to="/" end>Classement</NavLink>
          <NavLink to="/secteurs">Secteurs</NavLink>
          <NavLink to="/avance">Mode avancé</NavLink>
          <NavLink to="/lexique">Lexique</NavLink>
        </nav>
        <LiveBadge status={live} />
        <CompanySearch />
      </header>
      <main>
        <SourceBanner />
        <Suspense fallback={<p className="muted">Chargement…</p>}>
        <Routes>
          <Route path="/" element={<RankingPage />} />
          <Route path="/societe/:isin" element={<CompanyPage />} />
          <Route path="/secteurs" element={<SectorsPage />} />
          <Route path="/secteurs/:code" element={<SectorPage />} />
          <Route path="/avance" element={<AdvancedPage />} />
          <Route path="/lexique" element={<GlossaryPage />} />
          <Route path="*" element={<p>Page introuvable. <Link to="/">Retour au classement</Link></p>} />
        </Routes>
        </Suspense>
      </main>
      <footer className="muted">
        Analyse Parts note les sociétés à partir de leurs rapports annuels officiels (ESEF). Ceci n'est pas
        un conseil en investissement. Cours : Yahoo Finance (différé d'environ 15 minutes) et Euronext ;
        taux de change : BCE.
      </footer>
    </div>
  )
}
