# ADR 0002 — Stocker les faits XBRL bruts et calculer les indicateurs à la lecture

- **Statut** : Acceptée
- **Date** : 2026-11-06

## Contexte

Les chiffres des sociétés viennent des rapports annuels **ESEF**, récupérés au format xBRL-JSON
sur filings.xbrl.org. Ce qu'on a constaté sur 5 sociétés du CAC 40 (TotalEnergies, LVMH,
Air Liquide, Sanofi, Schneider Electric) :

1. **Un rapport pèse de 5 à 25 Mo**, surtout à cause des annexes en texte, pour seulement
   **300 à 600 faits chiffrés totaux** utiles.
2. **Une même notion porte des noms différents selon la société.** Le chiffre d'affaires est
   `ifrs-full:Revenue` chez LVMH, `RevenueFromContractsWithCustomers` chez Air Liquide,
   `RevenueFromSaleOfGoods` chez Sanofi. Certaines notions (investissements, résultat
   opérationnel) sont souvent publiées sous un concept propre à la société (`airliquide:…`).
3. **Chaque montant apparaît plusieurs fois** : le total et ses ventilations selon des axes
   (secteurs, composantes des capitaux propres…).
4. **Un rapport reprend l'exercice précédent**, parfois retraité. 3 rapports donnent jusqu'à
   5 exercices.
5. **La devise varie** : TotalEnergies publie en dollars.

La correspondance « notion → concept » sera donc imparfaite au début et va s'améliorer au fil
du projet.

## Décision

**On stocke tous les faits chiffrés totaux de chaque rapport, et on calcule les indicateurs
à la lecture, en Java pur.**

- Table `filing` : un rapport retenu par exercice (le moins d'erreurs de validation, puis celui
  du pays de la société).
- Table `reported_fact` : les faits **sans axe** et chiffrés, sans retouche (concept, période,
  unité, valeur, précision).
- `Metric` (enum) : pour chaque indicateur, sa nature (flux sur l'exercice, montant au bilan,
  montant par action) et une **liste ordonnée de concepts candidats**.
- `MetricExtractor` : fonction pure qui choisit, pour chaque exercice et chaque indicateur, le
  premier concept candidat présent, avec la bonne période et la bonne unité. En cas de doublon,
  le rapport le plus récent l'emporte, car il intègre les retraitements.
- Chaque valeur renvoyée garde sa **provenance** (concept, rapport) pour pouvoir expliquer
  un score.

## Alternatives écartées

### A. Une table « large » d'indicateurs (une colonne par indicateur, une ligne par exercice)

- **Pour** : requêtes SQL simples, lecture directe.
- **Pourquoi écartée** : le calcul serait figé au moment de l'import. Chaque amélioration de
  la correspondance (nouveau concept candidat, nouvelle règle) obligerait à **retélécharger
  tous les rapports**, soit des Go de données. Ajouter un indicateur demanderait aussi une
  migration de schéma.

### B. Stocker le fichier xBRL-JSON complet (JSONB ou fichier)

- **Pour** : rien n'est perdu, y compris les annexes en texte (utiles plus tard pour le LLM).
- **Pourquoi écartée pour l'instant** : 5 à 25 Mo par rapport et par exercice, sur l'offre
  gratuite d'Oracle Cloud. Et 99 % de ce volume est du texte dont le scoring n'a pas besoin.
  Si l'analyse LLM a besoin des annexes (Phase 5), on les téléchargera à ce moment-là, en batch
  local, depuis l'URL conservée dans `filing.json_url`.

### C. Ne stocker que les faits des concepts connus (ceux de `Metric`)

- **Pour** : beaucoup moins de lignes.
- **Pourquoi écartée** : on perdrait justement les concepts qu'on ne connaît pas encore,
  ceux propres aux sociétés et les candidats à ajouter. Les découvrir (comme
  `RevenueFromSaleOfGoods` chez Sanofi) se fait par une simple requête SQL sur les faits
  stockés. Le volume total reste modeste : environ 1 000 lignes par société.

### D. Garder aussi les faits ventilés selon des axes

- **Pour** : on aurait les données par secteur d'activité ou par pays.
- **Pourquoi écartée pour l'instant** : le volume serait plusieurs fois plus gros, et le
  scoring V1 n'utilise que des totaux. À revoir si une analyse sectorielle fine est ajoutée.

## Conséquences

**Positives**
- Améliorer le calcul = modifier `Metric` ou `MetricExtractor`, sans réimport.
- Calcul testable en millisecondes (tests unitaires sans base).
- Chaque chiffre est traçable jusqu'au concept et au rapport d'origine.

**Négatives / à surveiller**
- Le calcul est refait à chaque lecture. C'est négligeable pour une société (environ 1 000
  faits), mais il faudra peut-être un cache ou une table calculée quand le scoring tournera
  sur tout l'univers.
- Certains indicateurs restent vides tant que la société utilise un concept propre. Les
  extensions ESEF sont « ancrées » à un concept IFRS dans la taxonomie de la société, mais
  cette information n'est pas dans le xBRL-JSON. À traiter plus tard si la couverture
  l'exige.
- Les montants sont dans la devise de publication : il faudra convertir (ou ne comparer que
  des ratios, qui n'ont pas d'unité) avant de comparer des sociétés entre elles.
