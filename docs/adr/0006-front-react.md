# ADR 0006 — Front : React + TypeScript avec Vite, TanStack Query et React Router

- **Statut** : Acceptée
- **Date** : 2026-11-06

## Contexte

Phase 3 de la feuille de route : afficher le classement par profil et la fiche de chaque
société, avec un score explicable. Le front doit rester simple à comprendre (le propriétaire
apprend React) et se brancher sur l'API Spring Boot existante sans la modifier en profondeur.

## Décision

| Besoin | Choix | Pourquoi |
|---|---|---|
| Outillage | **Vite** + TypeScript | démarrage instantané, standard actuel (Create React App est abandonné) |
| Navigation | **React Router** | une URL par écran : `/` et `/societe/:isin`, profil dans `?profil=` (liens partageables) |
| Données | **TanStack Query** | cache, états chargement/erreur, pas de requête en double ; évite les `useEffect` + `fetch` faits à la main |
| Style | **CSS simple** avec variables (thème clair et sombre) | rien de plus à apprendre ; un seul fichier `index.css` |
| Appels API | **proxy Vite** en développement (`/api` → `localhost:8080`) | une seule origine pour le navigateur, donc pas de configuration CORS |

- Les types TypeScript (`src/api/types.ts`) recopient les records Java : à mettre à jour
  ensemble.
- Deux petits endpoints ajoutés pour le front : `GET /api/companies` (liste pour la recherche)
  et `GET /api/companies/{isin}` (identité), `GET /api/companies/{isin}/quote` (dernier cours).
- L'analyse du LLM aura sa place **à côté** du score (cadre réservé sur la fiche), conformément
  au document de conception.

## Alternatives écartées

- **Next.js** : rendu serveur et routage par fichiers inutiles ici (pas de SEO, une API Java
  existe déjà) ; une couche de plus à apprendre.
- **Angular** (déjà connu du propriétaire) : le projet sert justement à apprendre React.
- **Tailwind / bibliothèque de composants (MUI…)** : rapides, mais cachent le CSS ; à
  reconsidérer si l'interface grossit.
- **Redux / Zustand** : pas d'état global à partager hormis les données serveur, déjà gérées
  par TanStack Query ; le reste vit dans l'URL ou dans les composants.

## Conséquences

- En production (Phase 6), le front sera servi en fichiers statiques (`npm run build`) derrière
  le même domaine que l'API, ou l'API devra autoriser son origine (CORS).
- Graphiques : Recharts, voir l'ADR 0007.
