# ADR 0005 — Sources : AMF pour les rapports récents, Euronext pour les secteurs, Yahoo et BCE pour les cours

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
| Rapports ESEF | **AMF** (open data DILA, `info-financiere.gouv.fr`, jeu `flux-amf-new-prod`), en complément de filings.xbrl.org | chaque lundi, pour les sociétés dont un rapport est attendu |
| Secteur ICB, actions admises | **fiche Euronext** (`live.euronext.com`, blocs HTML publics) | une fois par mois et par société |
| Cours en séance | **Yahoo Finance** (`/v7/finance/spark`, 20 titres par requête, ~15 min de différé) | toutes les 15 min de 9 h à 17 h 45 |
| Cours de repli | **clôture Euronext**, déjà présente dans la liste des actions importée chaque jour | quotidien |
| Taux de change | **BCE** (taux de référence, `eurofxref-daily.xml`) | chaque jour ouvré à 16 h 30 |

- L'AMF est le **mécanisme officiel de stockage** des informations réglementées en France :
  les rapports y arrivent le jour de leur publication. Les rapports y sont au format d'origine
  (paquet ZIP + XHTML balisé en Inline XBRL) : ils sont lus par `InlineXbrlParser`, avec les
  mêmes règles que les rapports xBRL-JSON (faits totaux sans axe).
- Un exercice déjà présent (quelle que soit la source) n'est pas réimporté.
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
- **AMF seule** (sans filings.xbrl.org) : il faudrait télécharger 3 rapports de 5 à 20 Mo par
  société pour reconstituer l'historique, contre un JSON compact ; on garde filings.xbrl.org
  pour l'historique et l'AMF pour combler les années récentes.

## Conséquences

- Yahoo n'est pas une source officielle : elle peut changer sans préavis. Le cours de clôture
  Euronext garantit qu'un cours d'au plus un jour reste disponible.
- Les pages Euronext sont lues sans API documentée : rythme volontairement lent (1 société/s,
  une fois par mois), et l'analyseur est testé sur des pages réelles enregistrées.
- La source AMF ne couvre que les sociétés déposant en France. Pour les autres places
  européennes (Phase 6), il faudra leurs mécanismes officiels (ou ESAP, le point d'accès
  unique européen, quand il sera ouvert).
