# ADR 0020 — Les sociétés de toute l'Europe

- **Statut** : Acceptée
- **Date** : 2026-11-10
- **Complète** : [ADR 0002](0002-stockage-donnees-financieres.md) (stockage), [ADR 0004](0004-baremes-par-secteur.md) (barèmes par secteur), [ADR 0016](0016-demo-publique-gratuite.md)
  (démo gratuite)

## Contexte

L'application ne suivait que les 330 sociétés d'Euronext Paris. Objectif : toute l'Europe, sans
argent ni carte bancaire. Trois contraintes :

- **les données** : les rapports ESEF structurés n'existent que pour l'Europe, et la source gratuite
  (filings.xbrl.org) ne couvre pas tous les pays ; il n'existe pas de liste gratuite et stable des
  sociétés cotées hors Euronext ;
- **la place** : la base gratuite (Neon) est limitée ; 330 sociétés prenaient 84 Mo, dont 34 Mo de
  faits comptables et 37 Mo de cours ;
- **le secteur** : le barème des notes (ADR 0004) compare chaque société à son super-secteur ICB, et
  les banques et assurances ont leurs poids ; l'ICB vient des fiches Euronext.

## Décision

**1. Les 7 marchés réglementés d'Euronext** (Paris, Amsterdam, Bruxelles, Lisbonne, Dublin, Oslo,
Milan) : leurs listes officielles, comme Paris (`POST /api/admin/ingestion/euronext`, sans `mic`).
Une action cotée sur plusieurs places n'est prise que dans la liste de son marché principal (le
premier de la colonne « Market ») : elle y a son mnémonique, son cours et sa fiche. Les marchés
Growth et Access ne sont pas suivis. Les places et leurs symboles Yahoo sont dans `StockExchange`.

**2. Les autres places, par les émetteurs ESEF** (`EsefIssuerImportService`, chaque lundi) :

1. l'index de filings.xbrl.org donne les émetteurs et leur LEI (7 221, dont 4 078 avec un rapport
   depuis mi-2024) ; ceux des pays d'Euronext et d'Ukraine (plus de 1 000 émetteurs non cotés) sont
   écartés ;
2. GLEIF donne les codes ISIN du LEI (actions, obligations, ADR) ;
3. la recherche de Yahoo dit lequel est une action, sur quelle place ; si la place est suivie
   (Stockholm, Copenhague, Helsinki, Madrid, Athènes, Vienne, Londres, Vilnius, Riga, Tallinn,
   Budapest), la société est créée avec son LEI ; ses rapports suivent par l'import ESEF habituel.

Chaque émetteur examiné est noté (`esef_issuer_check`, V18) pour ne pas refaire ses recherches.

**3. Le secteur hors Euronext** vient de Yahoo, traduit en ICB par `yahoo-icb.csv` : pour chaque
sous-secteur Yahoo, l'ICB le plus fréquent parmi les 945 sociétés d'Euronext qui ont les deux
(scripts `secteurs-yahoo.js` et `construire-yahoo-icb.js`). Mesure sur ces sociétés : super-secteur
juste dans 83 % des cas, banques et assurances justes 76 fois sur 78. Un sous-secteur Yahoo partagé
sans majorité, ou appuyé sur une ou deux sociétés d'une autre industrie, est écarté ; un financier
inconnu va aux services financiers, pas aux banques.

**4. Une base plus légère** (V17) :

- seuls les faits qu'un indicateur peut utiliser sont gardés (`Metric.isUsed`, 38 concepts sur plus
  de 10 000) : 220 000 lignes deviennent 37 000, sans changer aucune note (vérifié sur les 300 notes
  des profils Qualité et Croissance) ;
- au-delà d'un an, une clôture par semaine et par fin de mois (celle-ci donne le cours exact à la
  clôture des exercices, qui sert aux ratios des années passées) : 2,5 fois moins de lignes ;
- résultat sur la base locale : 84 Mo deviennent 31 Mo, à couverture égale.

**5. Les devises** : Londres cote en pence, ramenés en livres dès la lecture de Yahoo
(`YahooQuoteClient.toMainUnit`) ; les autres devises (SEK, DKK, NOK…) étaient déjà converties par les
taux de la BCE. Le classement a un filtre par pays.

## Conséquences

- Environ 1 000 sociétés sur Euronext, et plus d'un millier d'autres en Europe.
- **Pas d'Allemagne ni de Pologne** : leurs rapports ESEF ne sont pas sur filings.xbrl.org, et le
  registre allemand n'est pas une source automatisable gratuite. Pas d'Islande, de Malte, de Slovénie
  ni de Croatie non plus (Yahoo ne les connaît pas, ou seulement à Francfort).
- Le secteur d'une société hors Euronext est une estimation (1 fois sur 6 faux au niveau du
  super-secteur) : son barème sectoriel peut être celui d'un secteur voisin.
- L'import initial est long (une journée : téléchargement des rapports, 2 s entre deux requêtes à
  filings.xbrl.org) : il se fait en local, puis la base est recopiée vers Neon.
- Un rapport en anglais, suédois ou italien est lu par le LLM comme les autres (les passages sont
  trouvés par le sens, ADR 0017) ; ses citations restent dans la langue du rapport.
- Ajouter un concept candidat à un indicateur demande de réimporter les rapports.
