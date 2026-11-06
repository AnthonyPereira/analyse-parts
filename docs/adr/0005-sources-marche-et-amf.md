# ADR 0005 — Sources : AMF en priorité pour les rapports, Euronext pour les secteurs, Yahoo et BCE pour les cours

- **Statut** : Acceptée
- **Date** : 2026-11-06

## Contexte

Trois besoins nouveaux, toujours à coût zéro :

1. **Rapports annuels récents** : filings.xbrl.org (ADR 0002) n'avait pas, en novembre 2026,
   les rapports 2025 de 38 sociétés pourtant publiés au printemps (LVMH, Air Liquide…).
2. **Secteur** et **nombre d'actions** de chaque société.
3. **Cours de bourse** en séance (profil Value) et **taux de change** (sociétés qui publient
   leurs comptes en dollars mais cotent en euros).

## Décision

| Besoin | Source | Rythme |
|---|---|---|
| Rapports ESEF | **AMF en priorité** (open data DILA, `info-financiere.gouv.fr`, jeu `flux-amf-new-prod`) ; filings.xbrl.org seulement pour les exercices manquants | chaque lundi, pour les sociétés dont un rapport est attendu |
| Secteur ICB, actions admises | **fiche Euronext** (`live.euronext.com`, blocs HTML publics) | une fois par mois et par société |
| Cours en séance | **Yahoo Finance** (`/v7/finance/spark`, 20 titres par requête, ~15 min de différé) | toutes les 15 min de 9 h à 17 h 45 |
| Cours de repli | **clôture Euronext**, déjà présente dans la liste des actions importée chaque jour | quotidien |
| Taux de change | **BCE** (taux de référence, `eurofxref-daily.xml`) | chaque jour ouvré à 16 h 30 |

- L'AMF est le **mécanisme officiel de stockage** des informations réglementées en France :
  les rapports y arrivent le jour de leur publication. Les rapports y sont au format d'origine
  (paquet ZIP + XHTML balisé en Inline XBRL) : ils sont lus par `InlineXbrlParser`, avec les
  mêmes règles que les rapports xBRL-JSON (faits totaux sans axe).
- **Ordre des sources** (révisé le 2026-11-06 à la demande du propriétaire) : l'AMF d'abord,
  car c'est la source officielle et la plus rapide. filings.xbrl.org n'est interrogé que s'il
  manque un des 3 derniers exercices (sociétés étrangères cotées à Paris, dépôts anciens).
- Les deux sources publient le **même document ESEF** : un exercice déjà présent, quelle que soit
  sa source, n'est pas réimporté. Les exercices importés avant ce changement depuis
  filings.xbrl.org sont donc conservés (mêmes chiffres).
- Le cours le plus récent gagne, quelle que soit sa source.
- Le nombre d'actions admises (Euronext) sert à la capitalisation ; à défaut, il est retrouvé
  par résultat net ÷ BPA (définition du BPA).

## Alternatives écartées

- **Cours en séance d'Euronext** : les réponses sont chiffrées côté serveur. Les déchiffrer
  reviendrait à contourner une protection volontaire : écarté (comme pour la composition des
  indices, cf. Phase 1).
- **API payantes** (EOD Historical Data, Financial Modeling Prep…) : contraire au coût zéro ;
  à reconsidérer si Yahoo disparaît.
- **Stooq** : historique quotidien seulement, téléchargements désormais protégés par captcha.
- **Secteur via Wikidata** : très incomplet pour les sociétés françaises.
- **AMF seule** (sans filings.xbrl.org) : les sociétés étrangères cotées à Paris (ArcelorMittal,
  Stellantis…) déposent dans leur pays, pas à l'AMF ; filings.xbrl.org reste utile pour elles.
- **filings.xbrl.org d'abord** (choix initial) : rapports compacts, mais en retard de plusieurs
  mois et très limité en nombre de requêtes.

## Conséquences

- Yahoo n'est pas une source officielle : elle peut changer sans préavis. Le cours de clôture
  Euronext garantit qu'un cours d'au plus un jour reste disponible.
- Les pages Euronext sont lues sans API documentée : rythme volontairement lent (1 société/s,
  une fois par mois), et l'analyseur est testé sur des pages réelles enregistrées.
- La source AMF ne couvre que les sociétés déposant en France. Pour les autres places
  européennes (Phase 6), il faudra leurs mécanismes officiels (ou ESAP, le point d'accès
  unique européen, quand il sera ouvert).
