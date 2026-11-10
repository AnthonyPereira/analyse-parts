# ADR 0018 — Points de la lecture des rapports classés par importance

- **Statut** : Acceptée
- **Date** : 2026-11-10
- **Complète** : [ADR 0012](0012-lecture-des-rapports-plus-complete.md) (plusieurs lots par thème),
  [ADR 0014](0014-lecture-des-rapports-moins-de-doublons.md) (doublons regroupés)

## Contexte

La fiche société affiche jusqu'à 8 points sur l'activité, 8 sur les avantages et 16 sur les
risques. Ils apparaissaient dans l'ordre de lecture : le premier lot de passages d'abord, puis le
suivant. Le modèle classe bien ses points à l'intérieur d'un lot (« du plus important au moins
important »), mais deux lots ne se comparent pas : le risque le plus grave pouvait arriver en
dixième position. Un débutant lit les premiers points et s'arrête souvent là.

Augmenter le nombre de points a été écarté : 15 risques suffisent à un débutant, s'ils sont dans
le bon ordre.

## Décision

Après le regroupement des doublons, un dernier appel au modèle par thème lui donne la liste
entière (titre et explication de chaque point) et lui demande les numéros dans l'ordre
d'importance, avec un critère propre au thème (`Topic.getImportanceRule`) :

- **activité** : d'abord ce qui pèse le plus dans le chiffre d'affaires ou le résultat ;
- **avantages** : d'abord les avantages durables et prouvés par des faits ;
- **risques** : d'abord ceux qui pourraient le plus toucher les ventes, les marges, la dette ou
  l'image, en tenant compte de l'importance que le rapport leur donne.

Le code applique l'ordre sans jamais perdre un point : un numéro inconnu ou répété est ignoré, un
point oublié garde sa place relative à la fin, et une réponse illisible laisse l'ordre de lecture.

La colonne `report_analysis.ranked_at` (migration V16) note la date du classement. Les lectures
faites avant ont été classées sans relire les rapports (`POST /api/admin/analysis/rank`, environ
5 s par société, dans la même file que les lectures). La fiche indique « du plus important au moins
important, selon le modèle » quand la lecture est classée.

## Conséquences

- Les premiers points de chaque thème sont les plus utiles ; un seul appel court par thème
  (quelques secondes), négligeable devant les 5 minutes d'une lecture.
- L'ordre est un jugement du modèle, pas une mesure : la fiche le dit. Il n'entre pas dans le
  score, comme le reste de la lecture.
- Pas de note d'importance chiffrée par point : un classement relatif suffit à l'affichage et
  évite de faire croire à une précision que le modèle n'a pas.
