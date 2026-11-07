# ADR 0009 — Retrait de Kafka

- **Statut** : Acceptée
- **Date** : 2026-11-07
- **Remplace** : [ADR 0008](0008-kafka.md)
- **Concerne** : [ADR 0001](0001-monolithe-modulaire.md) (Kafka prévu en phase 4), [ADR 0007](0007-cours-en-direct-et-historiques.md)

## Contexte

La feuille de route prévoyait une phase 4 « Kafka », pour apprendre une architecture
événementielle et en faire une vitrine. Trois étapes ont été réalisées (commits `90df462`,
`e54a552`, `bc7d9e4`) :

1. Kafka 4 (KRaft) et Kafka UI dans Docker Compose ;
2. un topic `quotes` entre la collecte des cours et le direct (SSE) ;
3. un topic `filings` publié par l'outbox de Spring Modulith, lu par un consommateur qui
   alimente l'historique des notes.

En préparant l'étape 4 (dead letter topic, rejeu), le bilan est apparu clairement :

- l'application tourne en **une instance**, pour **un utilisateur**. Kafka transportait des
  données que notre code avait déjà récupérées, sans rien apporter que les événements Spring
  Modulith ne font déjà ;
- sur `quotes`, il **ajoutait une panne possible** (Kafka arrêté = plus de direct) ;
- sur `filings`, la garantie « aucun rapport oublié » vient de la table `event_publication`,
  pas de Kafka ;
- l'étape 4 aurait ajouté des outils (DLT, rejeu) qui n'existent que pour gérer Kafka lui-même.

Kafka ne surveille pas non plus les sources (Yahoo, AMF) : il ne contient que ce qu'on y écrit.
La détection des nouveautés et des pannes reste le travail du code d'ingestion.

## Décision

- **Retirer Kafka** : conteneurs, dépendances, topics, tests et guide.
- **Garder l'historique des notes** (étape 3), qui ne dépend pas de Kafka : l'événement
  `FilingRecorded` est publié dans la transaction du rapport et traité par un
  `@ApplicationModuleListener` (après le commit, en arrière-plan, rejoué s'il échoue grâce à
  `event_publication` et `FilingOutboxRetry`).
- Le direct revient à la version de l'[ADR 0007](0007-cours-en-direct-et-historiques.md) :
  `QuotesUpdated` (événement Spring) → `QuoteStream` → SSE.
- Kafka sera appris dans un **projet séparé**, conçu pour un cas où il est utile.

## Quand Kafka deviendrait utile ici

- plusieurs applications distinctes qui consomment les mêmes faits (par exemple un service
  d'alertes déployé à part) ;
- plusieurs instances de l'application à tenir synchronisées ;
- des volumes que PostgreSQL ne suffit plus à absorber.

L'architecture le permet sans réécriture : les modules communiquent déjà par événements, que
Spring Modulith sait recopier dans Kafka (`spring-modulith-events-kafka`). Le code des trois
étapes reste consultable dans l'historique Git.

## Conséquences

- Un conteneur de moins ; les tests d'intégration ne démarrent plus de broker.
- Les notions apprises (topic, partition, offset, groupe, outbox, idempotence) sont décrites
  dans le guide `docs/kafka.md` du commit `bc7d9e4`.
