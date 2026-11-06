// Point d'entrée : React prend la main sur la <div id="root"> de index.html.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { App } from './App'
import './index.css'

// Le cache des données de l'API, partagé par toute l'application.
// staleTime : une donnée de moins d'une minute est réutilisée sans nouvelle requête.
const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 60_000, retry: 1 } },
})

// Les « Provider » rendent un service disponible à tous les composants en dessous :
// le cache (QueryClientProvider) et la navigation par URL (BrowserRouter).
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
