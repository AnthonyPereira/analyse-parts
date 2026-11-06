# ADR 0003 — Score par barèmes absolus, pondérés par profil

- **Statut** : Acceptée — complétée par l'ADR 0004 (barèmes par secteur)
- **Date** : 2026-11-06

## Contexte

Il faut transformer une quinzaine de ratios (voir [docs/ratios.md](../ratios.md)) en un
score de 0 à 100, selon le profil choisi (Qualité, Croissance, Dividende). Contraintes :

1. **Explicable** : l'utilisateur doit pouvoir lire « ROE de 19,5 % → 86 points × 27 % du
   poids = 23 points de score ».
2. **Stable** : le score d'une société ne doit changer que si *ses* chiffres changent.
3. **Données incomplètes** : selon le ratio, 22 % à 100 % des sociétés ont la donnée ; certains
   ratios n'ont pas de sens pour les banques ; certaines sources ont des rapports en retard.
4. **Recalculable** : un changement de barème ou de poids ne doit demander aucune migration.

## Décision

1. **Un barème absolu par ratio** : des points d'ancrage (valeur → points) avec
   interpolation linéaire entre eux (`Scale`). Les ancrages sont des repères d'analyse
   financière généralistes (ex. ROE de 15 % = bon).
2. **Moyenne pondérée** des points par les poids du profil (total 100). Chaque contribution
   est renvoyée par l'API avec les chiffres et concepts XBRL d'origine.
3. **Ratio indisponible = retiré, jamais inventé.** Son poids est réparti sur les autres, et
   la raison est donnée. Si moins de 60 % du poids est disponible (la *couverture*), pas de score.
4. **Pertinence** : les ratios de structure de bilan (gearing, autonomie, liquidité) sont
   déclarés non pertinents quand le bilan est présenté par ordre de liquidité (banques,
   assurances), détecté par l'absence d'actifs et de passifs courants.
5. **Fraîcheur** : au-delà de 16 mois après la clôture le score est signalé « en retard »,
   au-delà de 28 mois il n'est pas calculé.
6. **Calcul à la demande**, en Java pur (`scoring.core`), sans stockage : 224 sociétés sont
   notées en environ une seconde.

## Alternatives écartées

### A. Rang relatif (percentile dans l'univers)
« ROE meilleur que 80 % des sociétés → 80 points ».
- **Pour** : s'adapte tout seul aux niveaux réels, pas de seuils à choisir.
- **Pourquoi écartée** : le score d'une société change quand *d'autres* sociétés publient ou
  entrent dans l'univers, ce qui viole la stabilité et rend l'explication indirecte. Et dans un
  marché entier de mauvaise qualité, le meilleur obtiendrait quand même 100.

### B. Barèmes par secteur
Une marge de 5 % est excellente pour un distributeur, médiocre pour un éditeur de logiciels.
- **Pour** : plus juste.
- **Pourquoi reportée** : nous n'avons pas encore de classification sectorielle fiable et
  gratuite (le CSV Euronext n'en donne pas). Le seul cas où l'erreur est flagrante (banques
  et assurances) est traité par la règle de pertinence. À reprendre quand on aura les secteurs.

### C. Remplacer une donnée manquante par une valeur neutre (50 points) ou par la médiane
- **Pourquoi écartée** : cela invente de l'information. Une société qui ne publie pas ses
  investissements recevrait une note sur une donnée inexistante. Retirer le ratio et afficher
  la couverture est plus honnête.

### D. Modèle statistique ou appris (régression, score de type Piotroski, machine learning)
- **Pourquoi écartée pour l'instant** : moins explicable, et sans historique de cours on ne
  peut pas valider qu'il prédit quoi que ce soit. Le backtest est prévu en Phase 6 ; le
  F-score de Piotroski pourra alors être ajouté comme ratio supplémentaire.

### E. Stocker les scores en base
- **Pourquoi écartée** : il faudrait les invalider à chaque nouveau rapport, nouveau concept
  ou changement de barème. Le calcul est assez rapide pour être fait à la demande.

## Conséquences

- Les barèmes sont des choix d'auteur : ils sont publics (API `/api/scoring/ratios` et
  `docs/ratios.md`) et testés.
- Les banques et assurances ont rarement assez de ratios pour un score Qualité : il leur
  faudra des ratios spécifiques (coefficient d'exploitation, ratio de solvabilité…).
- Les secteurs à forte intensité capitalistique ou à faibles marges (distribution, BTP) sont
  structurellement désavantagés tant qu'il n'y a pas de barèmes par secteur.
