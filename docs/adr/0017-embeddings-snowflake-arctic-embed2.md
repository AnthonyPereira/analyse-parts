# ADR 0017 — Embeddings : snowflake-arctic-embed2, recherches précédées de « query: »

- **Statut** : Acceptée
- **Date** : 2026-11-10
- **Remplace en partie** : [ADR 0011](0011-lecture-des-rapports-par-llm-local.md) (le modèle d'embedding)

## Contexte

Avant de lire un rapport, le code choisit les passages à donner au modèle de langage : 28 pour
l'activité et les avantages, 42 pour les risques, sur 650 à 2 600 passages par rapport. Ce choix
repose sur des embeddings. Un passage utile qui n'est pas choisi ne sera jamais lu.

L'ADR 0011 avait retenu `bge-m3` parce qu'il est multilingue, sans comparaison mesurée. Deux autres
modèles multilingues tournent dans Ollama : `qwen3-embedding:0.6b` et `snowflake-arctic-embed2`.
Tous deux ont été entraînés avec un préfixe devant les questions (une consigne pour qwen3,
« query: » pour arctic), mais pas devant les passages.

## Mesure

Banc d'essai `EmbeddingBenchmarkTest` (lancé avec `EMBEDDING_BENCHMARK=1`, environ 25 min) :

- 7 rapports annuels 2025 de secteurs différents : L'Oréal, BNP Paribas, Schneider Electric,
  Gecina, Sanofi, TotalEnergies, Capgemini ;
- chaque configuration choisit ses passages avec le code de production (`Topic.selectPassages`),
  686 en tout ;
- on réunit les passages choisis par au moins une configuration, et `qwen3:14b` juge chacun une
  fois, utile ou non pour le thème, sans savoir qui l'a choisi (évaluation par mise en commun,
  comme dans les campagnes TREC) ;
- précision = part des passages choisis qui sont utiles ; rappel relatif = part des passages utiles
  de la mise en commun que la configuration a trouvés.

| Configuration | Passages utiles | Précision | Rappel relatif | Indexation par rapport |
|---|---|---|---|---|
| snowflake-arctic-embed2 + « query: » | **237** | **35 %** | **56 %** | 17 s |
| snowflake-arctic-embed2 | 212 | 31 % | 50 % | 17 s |
| qwen3-embedding:0.6b + consigne | 205 | 30 % | 48 % | 21 s |
| bge-m3 | 201 | 29 % | 47 % | 17 s |
| qwen3-embedding:0.6b | 176 | 26 % | 42 % | 21 s |

Par thème, arctic + « query: » gagne surtout sur les risques (138 passages utiles contre 120) et
les avantages (36 contre 23) ; l'activité est à égalité (63 contre 58).

## Décision

`snowflake-arctic-embed2` remplace `bge-m3`, et chaque recherche est précédée de « query: »
(`analyse-parts.analysis.query-prefix`, dans `PassageSearch`). Les passages et les points comparés
entre eux (doublons) restent sans préfixe. Le modèle se change par `EMBEDDING_MODEL`.

## Conséquences

- 18 % de passages utiles en plus pour le même nombre de passages lus, donc pour le même temps de
  lecture par le modèle de langage ; même vitesse d'indexation, même taille (1,2 Go).
- Les lectures déjà en base, faites avec `bge-m3`, restent affichées ; une relecture
  (`POST /api/admin/analysis/{isin}`) utilise le nouveau modèle.
- Limites de la mesure : un seul essai, un juge qui est lui-même un LLM, et la qualité des points
  finaux n'est pas mesurée. À vérifier en relisant quelques sociétés.
- Les avantages restent le thème le plus faible (10 à 18 % de passages utiles, quel que soit le
  modèle) : les recherches de ce thème sont à revoir.
- Il faut `ollama pull snowflake-arctic-embed2` sur la machine qui lit les rapports.
