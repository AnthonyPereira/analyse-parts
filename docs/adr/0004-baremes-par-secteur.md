# ADR 0004 — Barèmes adaptés au secteur par calibrage figé

- **Statut** : Acceptée
- **Date** : 2026-11-06
- **Complète** : [ADR 0003](0003-methode-de-score.md) (dont l'alternative B, « barèmes par secteur », était reportée)

## Contexte

Avec un barème unique, un distributeur (marge nette normale : 2 à 3 %) est toujours mal noté
et un éditeur de logiciels (20 %) toujours bien, quelle que soit leur qualité *dans leur
métier*. Même chose pour l'endettement (normal pour une foncière ou un réseau électrique) ou
le PER (un éditeur de logiciels coûte structurellement plus cher qu'un cimentier).

Depuis la fiche Euronext, chaque société a sa classification **ICB** (Industry Classification
Benchmark, FTSE Russell) sur 4 niveaux : industrie (11), super-secteur (20), secteur, sous-secteur.

## Décision

1. **Médianes par secteur** : pour chaque ratio « de niveau » (marges, ROA, endettement,
   liquidité, cash-flow libre, PER, VE/EBIT, cours/valeur comptable), on calcule la médiane
   dans chaque super-secteur et chaque industrie, et dans tout l'univers.
2. **Facteur** = médiane du secteur ÷ médiane de l'univers, borné entre **0,4 et 2,5**.
   Chaque repère du barème général est multiplié par ce facteur :
   la forme du barème ne change pas, seule son échelle s'adapte.
3. **Niveau utilisé** : le super-secteur dès qu'au moins **3** de ses sociétés ont le ratio,
   même s'il est petit (assurance, matières premières, chimie à Paris) : choix explicite de garder
   la référence la plus proche du métier. En dessous de 3, la « médiane » serait la société
   elle-même ; on passe alors à l'industrie (au moins 8 sociétés), sinon au barème général.
4. **Ratios jamais ajustés** : ROE (l'actionnaire attend au moins le coût du capital, quel
   que soit le secteur), conversion en cash, croissance, régularité, distribution et
   rendements (un rendement de 5 % vaut 5 % partout).
5. **Calibrage figé** : les médianes sont calculées puis **enregistrées** (table
   `sector_calibration`) ; elles ne changent qu'à un recalibrage explicite
   (`POST /api/admin/scoring/calibrate`). Le score d'une société ne bouge donc pas parce
   qu'une autre a publié ses comptes : la règle de stabilité de l'ADR 0003 est préservée.
6. **Explicable** : chaque contribution du score indique le secteur de référence, sa médiane,
   celle de l'univers, le facteur et le nombre de sociétés.

## Alternatives écartées

### A. Rang dans le secteur (percentile)
Même défaut que le percentile global (ADR 0003) : le score dépend des autres sociétés en
continu, et dans un secteur de 8 sociétés un rang est très instable.

### B. Barèmes écrits à la main par secteur
- **Pour** : on peut y mettre son expertise.
- **Pourquoi écartée** : 20 super-secteurs × 11 ratios = 220 barèmes à justifier un par un,
  sans données pour les étayer. Les médianes observées sont un point de départ objectif ;
  rien n'empêche plus tard d'en corriger quelques-unes à la main.

### C. Ajustement au niveau du sous-secteur
Trop peu de sociétés par sous-secteur sur le seul marché de Paris (souvent 1 à 3).
À reconsidérer avec l'univers européen (Phase 6).

### D. Recalculer les médianes à chaque requête
Plus simple, mais viole la stabilité ; et les médianes de valorisation (PER) bougeraient
avec les cours chaque jour.

## Conséquences

- Les banques (super-secteur « Banks ») restent hors des ratios de bilan (règle de l'ADR 0003)
  et sont trop peu nombreuses à Paris pour une médiane propre sur la plupart des ratios.
- Un secteur entier en difficulté tire sa médiane vers le bas : la borne de 0,4 limite l'effet.
- Il faut recalibrer après un gros import (nouveaux rapports annuels au printemps,
  extension à d'autres places).
