import { Link, Route, Routes } from 'react-router'
import { CompanySearch } from './components/CompanySearch'
import { CompanyPage } from './pages/CompanyPage'
import { RankingPage } from './pages/RankingPage'

/**
 * La mise en page commune (en-tête, pied de page) et les routes : l'URL choisit la page
 * affichée au milieu, sans recharger le navigateur (« Single Page Application »).
 */
export function App() {
  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand">Analyse Parts</Link>
        <CompanySearch />
      </header>
      <main>
        <Routes>
          <Route path="/" element={<RankingPage />} />
          <Route path="/societe/:isin" element={<CompanyPage />} />
          <Route path="*" element={<p>Page introuvable. <Link to="/">Retour au classement</Link></p>} />
        </Routes>
      </main>
      <footer className="muted">
        Analyse Parts note les sociétés à partir de leurs rapports annuels officiels (ESEF). Ceci n'est pas
        un conseil en investissement. Cours : Yahoo Finance (différé) et Euronext ; taux de change : BCE.
      </footer>
    </div>
  )
}
