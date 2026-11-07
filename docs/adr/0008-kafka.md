# ADR 0008 — Kafka pour les événements entre modules

- **Statut** : Acceptée
- **Date** : 2026-11-07
- **Complète** : [ADR 0001](0001-monolithe-modulaire.md), [ADR 0007](0007-cours-en-direct-et-historiques.md)

## Contexte

Les modules communiquent par des événements Spring en mémoire (`QuotesUpdated`,
`CompaniesImported`). C'est suffisant pour une seule instance, mais :

- un événement en cours de traitement est perdu si l'application s'arrête ;
- personne ne peut le relire (un nouveau traitement ne voit que les événements futurs) ;
- on ne garde aucune trace de ce qui s'est passé.

Le projet sert aussi à apprendre une architecture événementielle (objectif « vitrine » du
document de conception). Les usages à venir s'y prêtent : un rapport annuel ou semestriel
importé devra déclencher le recalcul des notes, leur historique, puis l'analyse par le LLM
local (phase 5), sans que l'import connaisse ces traitements.

## Décision

1. **Kafka 4 en mode KRaft**, un seul broker, dans `compose.yaml` ; **Kafka UI** pour observer
   topics, messages et groupes. Testcontainers démarre la même image pour les tests.
2. **Un topic par type de fait**, avec l'**ISIN pour clé** : tous les messages d'une société
   sont dans la même partition, donc dans l'ordre. Messages en **JSON texte** (contrat :
   `QuoteMessage`), lisibles dans Kafka UI.
3. **Topic `quotes`** (étape 2) : chaque cours qui change. 3 partitions, rétention 1 jour.
   - Producteur : `market.internal.QuotePublisher`, à partir de l'événement `QuotesUpdated`.
   - Consommateur : `QuoteStream` (groupe `quote-stream`), qui pousse les cours au navigateur
     par SSE. Le front ne change pas.
   - Garantie **au plus une fois**, sans outbox : si Kafka est arrêté, le cours est perdu pour
     le direct mais reste en base, et le suivant arrive une minute plus tard. `max.block.ms`
     à 5 s pour ne pas bloquer la collecte.
4. **Topic `filings`** (étape 3) : un rapport enregistré (`FilingRecorded`). Il ne doit pas
   être perdu :
   - publication par l'**outbox** de Spring Modulith : l'événement est écrit dans
     `event_publication` dans la transaction du rapport, puis envoyé à Kafka
     (`spring-modulith-events-kafka`, règle dans `financials.internal.FilingTopic`) ; renvoi au
     redémarrage et toutes les 5 minutes (`FilingOutboxRetry`) ;
   - garantie **au moins une fois** : le consommateur `score-history` (module scoring) est
     **idempotent** (clé primaire de `score_history` + `ON CONFLICT DO UPDATE`) ;
   - **rétention illimitée** : un nouveau consommateur peut relire tous les rapports (phase 5).
   - Pas d'annotation `@Externalized` : sa clé en SpEL serait évaluée sur le JSON déjà écrit ;
     la route et la clé sont donc déclarées en Java.
5. **Erreurs** (étape 4) : nouvelles tentatives puis topic d'erreurs (dead letter topic).

## Conséquences

- Les notes ont désormais un historique (table `score_history`, migration V12) : à l'arrivée d'un
  rapport, chaque soir de bourse, et reconstitué pour les exercices passés.
- Docker Compose démarre un conteneur de plus ; sans Kafka, l'application démarre mais le
  direct ne reçoit plus de cours.
- Avec plusieurs instances de l'application, chacune aurait besoin de son propre groupe pour
  le direct (dans un même groupe, les instances se partagent les partitions).
- Les tests d'intégration démarrent un vrai Kafka (quelques secondes de plus).
- Notions et démonstration en ligne de commande : [docs/kafka.md](../kafka.md).
