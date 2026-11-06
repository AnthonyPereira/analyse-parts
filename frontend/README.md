# Analyse Parts — front

React 19 + TypeScript, construit avec Vite. Choix expliqués dans
[l'ADR 0006](../docs/adr/0006-front-react.md).

## Lancer

```bash
# le backend doit tourner sur le port 8080 (voir ../backend)
npm install
npm run dev        # http://localhost:5173 ; /api est relayé vers le backend
npm run build      # vérifie les types puis produit dist/
```

## Organisation

| Dossier | Contenu |
|---|---|
| `src/api/` | types des réponses de l'API et accès aux données (TanStack Query) |
| `src/pages/` | une page par route : classement (`/`), fiche société (`/societe/:isin`) |
| `src/components/` | composants réutilisables (badges, onglets de profil, recherche, comptes) |
| `src/format.ts` | mise en forme à la française (%, montants, dates) |
