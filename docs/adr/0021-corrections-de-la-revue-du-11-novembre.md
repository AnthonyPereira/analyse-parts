# ADR 0021 — Corrections de la revue du 11 novembre 2026

- **Statut** : Acceptée
- **Date** : 2026-11-13
- **Complète** : [ADR 0003](0003-methode-de-score.md) et [ADR 0004](0004-baremes-par-secteur.md) (barèmes),
  [ADR 0016](0016-demo-publique-gratuite.md) (démo en ligne), [ADR 0020](0020-societes-de-toute-l-europe.md)
  (secteurs Yahoo)

## Contexte

La revue du projet du 11 novembre 2026 a trouvé : les routes `/api/admin/**` joignables sans jeton
avec une adresse déguisée (`/api/%61dmin/…`, `/api/admin;x=1/…`) ; un classement recalculé à chaque
visite (6 s sur la démo, 7 s pour l'univers) ; des mines d'or et d'argent comparées à la chimie ; des
notes de Qualité saturées (9 sociétés à 100, 94 à 90 ou plus sur 1 621) ; et des points d'entretien.

## Décision

**1. Filtre d'administration sur le chemin que Spring utilise** : décodé, sans paramètres `;`,
barres obliques doublées réduites (`UrlPathHelper`). Toute adresse sous `/api` qui contient encore
`;` ou `%` est refusée (400) : aucune route n'en a besoin.

**2. Classement et univers gardés en mémoire.** Tous les derniers cours et taux de change sont lus
en une requête et gardés une minute au plus (`MarketDataService`), au lieu d'une requête par
société. Le classement de chaque profil et l'univers sont gardés jusqu'au prochain changement :
nouveau cours (`QuotesUpdated`), nouveau rapport (`FilingRecorded`), recalibrage, changement de
jour, ou 15 minutes au plus. Après un changement, un recalcul en arrière-plan prépare le visiteur
suivant. Les écouteurs sont de simples `@EventListener` qui attendent le commit : un
`@TransactionalEventListener` serait suivi par Spring Modulith dans `event_publication`. Le mode
avancé (poids libres) n'est pas gardé, mais profite de la lecture groupée des cours.

**3. Secteurs Yahoo complétés** (`yahoo-icb.csv`) pour les sous-secteurs absents des sociétés
d'Euronext (or, argent, métaux précieux, charbon, uranium, raffinage, solaire, assurance vie et
spécialisée, courtiers, grands magasins, éducation, tabac, pharmacies, conglomérats).
`POST /api/admin/ingestion/esef-issuers/sectors` redemande leur secteur à Yahoo pour toutes les
sociétés hors Euronext et réapplique la table, en arrière-plan (une vingtaine de minutes, plus que
les 15 minutes qu'accorde Render à une requête) ; `GET` sur la même adresse dit si elle est finie.
Recalibrer ensuite.

**4. Haut des barèmes étalé** pour la rentabilité, la solidité et le cash-flow libre : l'ancien
repère « 100 points » vaut 90, et 100 demande un niveau exceptionnel (marge nette 35 %, ROE 40 %,
trésorerie nette égale à la moitié des capitaux propres…). Les autres barèmes ne changent pas.

**5. Entretien** : événements traités purgés après 7 jours ; table `index_membership` supprimée
(V20) ; lignes techniques d'Amsterdam (« TREAS SHARES », « BUY BACK », « DSC ») écartées à
l'import et supprimées (V21) ; cours accepté jusqu'à 31 jours (petites sociétés peu échangées) ;
intégration continue GitHub Actions (backend, lint, tests et build du site) ; premiers tests du site
(Vitest), dont un qui vérifie que chaque bulle d'aide a son entrée dans le lexique.

## Conséquences

- La page d'accueil ne recalcule plus 1 963 sociétés à chaque visite ; la première visite après un
  réveil du serveur attend encore le préchauffage.
- Les notes de Qualité baissent un peu pour les meilleures sociétés : l'historique des notes montre
  un saut à la date du changement.
- Le réveil du serveur (cron-job.org) reste à vérifier dans son historique : ce n'est pas du code.
