# ADR 0012 — Lecture des rapports plus complète, avec qwen3:14b

- **Statut** : Acceptée
- **Date** : 2026-11-08
- **Remplace en partie** : [ADR 0011](0011-lecture-des-rapports-par-llm-local.md) (le modèle, le
  contexte, les tableaux et le lot unique de passages). Le reste de l'ADR 0011 s'applique toujours :
  modèle local, citations vérifiées, rien de tout cela dans le score.

## Contexte

Le premier essai réel, sur le rapport de L'Oréal, a donné 5 risques et des points vides :

- tout le chapitre « Facteurs de risque » est rédigé **dans des tableaux**, que l'ADR 0011 écartait ;
- un seul lot de 14 passages ne couvre pas un rapport de 1 238 passages ;
- quand le modèle triait lui-même ses points, il écartait des risques importants (change, climat) ;
- l'ordre des champs du JSON imposé changeait à chaque démarrage (`Map.of`) : un jour sur deux, le
  modèle recopiait « [1] » au lieu d'une phrase (25 points écartés au lieu de 2).

## Décision

1. **qwen3:14b** par défaut, avec **10 240 tokens** de contexte : il tient presque entièrement en
   mémoire graphique (40 couches sur 41). Il trouve de meilleurs risques que qwen3:8b, mais il est
   3 fois plus lent (environ 3 min 30 par rapport, soit 9 h pour le SBF 120).
2. Les paragraphes rédigés **dans un tableau** sont gardés, avec leur titre (pas les cellules de
   chiffres).
3. **Plusieurs lots par thème** : 2 pour l'activité et les avantages, 3 pour les risques. Ils sont
   choisis par de nombreuses recherches, une par sous-sujet, et les sujets hors thème (plan de
   vigilance, comptes de la société mère, sommaire, statuts, règles comptables) sont écartés.
4. Pas de tri par le modèle. Deux points font **doublon** quand leurs titres *et* leurs
   explications ont des embeddings proches (cosinus ≥ 0,85).
5. Un point est écarté si sa citation est un simple titre (moins de 8 mots) ou si son explication
   ne fait que répéter la citation.
6. L'**ordre des champs** est fixe : extrait, citation, titre, explication. Le modèle trouve d'abord
   la phrase, puis la résume.
7. Pour l'activité, le premier lot est le **chapitre de présentation du groupe, lu d'un seul
   tenant** : les 14 passages qui se suivent et qui sont, en moyenne, les plus proches de ce que
   décrit une présentation (métier, organisation, marché, modèle d'affaires).
8. **Relecture** (réglable par `ANALYSIS_REVIEW`, désactivée par défaut : on préfère l'exhaustivité) : le modèle juge, en donnant
   d'abord sa raison, si chaque citation appuie vraiment son point.

## Mesures sur L'Oréal (qwen3:14b)

| Version | Durée | Activité | Avantages | Risques | Écartés |
|---|---|---|---|---|---|
| Référence (1 à 6) | 166 s | 8 | 8 | 14 | 3 |
| + chapitre de présentation (7) | 171 s | 8 | 8 | 14 | 1 |
| + relecture (8) | 219 s | 8 | 6 | 7 | 12 |

- **Chapitre** : les points sur l'activité viennent tous du chapitre 1.1 (divisions, zones,
  filiales). La lettre du président et un tableau de raccordement du bilan ont disparu. Il reste un
  défaut : chaque division apparaît deux fois. Un premier essai, qui partait du passage le plus
  proche de chaque titre de chapitre, faisait moins bien (4 points) : dans le texte, les titres sont
  fondus dans des passages qui parlent d'autre chose.
- **Relecture** : les rejets justes sont la plupart. Elle retire les règles comptables présentées
  comme des avantages, les objectifs environnementaux présentés comme des risques, et « aucun
  impact significatif » cité pour un risque climatique. Mais elle retire aussi des risques bien
  réels dont la citation était faible (change, cybersécurité), et un risque d'approvisionnement
  pourtant bien cité. Elle laisse aussi passer quelques points faibles (« faiblement exposé au
  risque de contrepartie »).

## Conséquences

- Les réponses sont plus justes, mais moins complètes : la relecture échange des points douteux
  contre des risques manquants. On préfère l'exhaustivité : elle est désactivée (`ANALYSIS_REVIEW=true` la réactive).
- Lire toute la cote prend environ 21 h avec qwen3:14b (plus de 24 h avec la relecture) : à faire en plusieurs nuits,
  ou pour le SBF 120 seulement.
- Piste suivante : quand la relecture rejette un point, redemander une meilleure citation au modèle
  au lieu de retirer le point.
