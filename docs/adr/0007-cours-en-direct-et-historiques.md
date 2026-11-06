# ADR 0007 — Cours en direct (SSE), historique des cours et des ratios

- **Statut** : Acceptée
- **Date** : 2026-11-06
- **Complète** : [ADR 0005](0005-sources-marche-et-amf.md), [ADR 0006](0006-front-react.md)

## Contexte

1. Le cours affiché ne bougeait pas : Yahoo était interrogé toutes les 15 minutes, et la page
   ne relisait le cours que toutes les 5 minutes.
2. Les graphiques demandent des historiques : cours quotidiens, ratios exercice par exercice
   (y compris la valorisation passée), et les ratios de toutes les sociétés pour comparer.

Yahoo diffuse les cours de Paris avec **environ 15 minutes de différé** (mesuré : cours de
15 h 10 lu à 15 h 25). Un vrai temps réel n'existe pas gratuitement ; on peut en revanche
suivre chaque mise à jour de Yahoo dès qu'elle paraît.

## Décision

### Direct
- Yahoo est interrogé **chaque minute** pendant la séance (9 h – 17 h 55, heure de Paris :
  clôture à 17 h 35 + différé), par lots de 20 titres : ~17 requêtes par minute pour 330 titres.
- Seuls les cours **qui ont changé** sont enregistrés (`INSERT … ON CONFLICT … WHERE` ne
  modifie rien si le cours est identique), puis publiés dans l'événement `QuotesUpdated`.
- Le navigateur reçoit ces cours par **Server-Sent Events** (`GET /api/quotes/stream`) :
  une réponse HTTP qui reste ouverte, dans laquelle le serveur écrit à chaque nouveau cours.
  Côté React, chaque message est écrit dans le cache de TanStack Query : le prix, la variation
  du jour et la courbe de la séance se mettent à jour sans recharger la page.
- La clôture de la veille est enregistrée avec le cours (`quote.previous_close`) pour la
  variation du jour.

### Historiques
- **Cours** : table `price_history`, clôtures quotidiennes Yahoo (5 ans chargés une fois, puis
  chaque soir à 18 h 15). Environ 400 000 lignes pour 320 sociétés.
- **Séance du jour** : lue à la demande chez Yahoo (un point toutes les 5 minutes), avec un
  cache d'une minute. Le module `market` déclare le besoin (`IntradaySource`, un « port ») et
  le module `ingestion` le réalise avec Yahoo : `market` ne dépend pas d'`ingestion`.
- **Ratios par exercice** : calculés à la demande, comme si l'on était à chaque date de
  clôture : seuls les exercices connus alors, le cours de ce jour-là, le nombre d'actions de
  l'époque (résultat net ÷ BPA).
- **Univers** (`GET /api/universe`) : ratios et 4 scores de chaque société, pour les nuages de
  points, les bandes de comparaison et les pages secteur (~2 s, gardé 5 min côté navigateur).

### Graphiques
- **Recharts** (librairie React de graphiques en SVG) ; diagramme en bandes écrit à la main en SVG.
- Palette validée (contraste et daltonisme) : bleu, orange, vert d'eau dans un ordre fixe ;
  légende dès deux séries ; un seul axe vertical par graphique ; infobulle sur chaque graphique.

## Alternatives écartées

- **WebSocket** : bidirectionnel, inutile ici (le navigateur n'envoie rien) ; SSE passe par du
  HTTP simple, se reconnecte tout seul et traverse le proxy de Vite sans configuration.
- **Relire le cours toutes les X secondes depuis le navigateur** (polling) : une requête par
  onglet et par société affichée, même quand rien ne bouge.
- **Kafka dès maintenant** : prévu en Phase 4 ; le flux de cours sera alors publié dans un
  topic avant d'arriver au SSE. L'événement `QuotesUpdated` est déjà le point d'extension.
- **Stocker chaque cours de la séance** : la courbe du jour de Yahoo suffit ; l'historique
  quotidien couvre le reste.

## Conséquences

- Les sociétés absentes de Yahoo (8, ex. Titan, Solvay, WDP) n'ont pas d'historique de cours.
- Les cours historiques de Yahoo sont ajustés des divisions d'actions, pas le BPA publié : le
  PER passé d'une société qui a divisé son action serait faux (cas rare, à surveiller).
- Les ratios passés sont convertis au taux de change du jour (approximation pour les
  sociétés qui publient en dollars).
