# Kafka dans Analyse Parts

Ce guide explique les notions de Kafka utilisées dans le projet, avec des commandes à essayer.
Les choix sont expliqués dans l'[ADR 0008](adr/0008-kafka.md).

## Démarrer

Kafka et son interface web sont dans `backend/compose.yaml`, à côté de PostgreSQL :

```bash
cd backend
docker compose up -d kafka kafka-ui
```

- Kafka écoute sur `localhost:9092` ;
- **Kafka UI** : <http://localhost:8081> (topics, messages, consommateurs).

L'application Spring Boot démarre aussi ces conteneurs toute seule (support Docker Compose).

## Les notions, en une page

| Notion | En une phrase | Équivalent familier |
|---|---|---|
| **Message** (record) | Une clé, une valeur (ici du JSON) et une date. | Une ligne ajoutée à un journal |
| **Topic** | Un journal thématique où l'on ajoute des messages à la suite, sans jamais modifier ni effacer les anciens. | Un fichier de log |
| **Partition** | Un topic est découpé en plusieurs journaux indépendants, lus en parallèle. | Les tomes d'une encyclopédie |
| **Offset** | Le numéro d'un message dans sa partition : 0, 1, 2… | Un numéro de ligne |
| **Producteur** | Celui qui écrit. Il ne sait pas qui lira. | `INSERT` |
| **Consommateur** | Celui qui lit, à son rythme, et retient où il en est. | Un marque-page |
| **Groupe de consommateurs** | Les consommateurs d'un même groupe se partagent les partitions ; chaque groupe reçoit tous les messages. | Une équipe qui se partage un travail |
| **Lag** (retard) | Nombre de messages qu'un groupe n'a pas encore lus. | Le courrier non lu |
| **Broker** | Le serveur Kafka qui stocke les partitions. | Le serveur de base de données |
| **Rétention** | Durée de conservation des messages, lus ou non (ici 7 jours). | La durée d'archivage |

Deux règles à retenir :

1. **La clé décide de la partition.** Tous les messages de même clé vont dans la même
   partition, donc **restent dans l'ordre**. Avec l'ISIN comme clé, les cours de L'Oréal
   arrivent toujours dans l'ordre où ils ont été produits. Entre deux partitions, aucun ordre
   n'est garanti.
2. **Lire ne supprime rien.** Le message reste dans le topic ; seul le marque-page (l'offset du
   groupe) avance. Un nouveau lecteur peut donc tout relire depuis le début : c'est ce qu'une
   file de messages classique (RabbitMQ, JMS) ne permet pas.

**KRaft.** Depuis Kafka 4, le cluster se gère lui-même : un nœud « controller » tient la liste
des topics et des partitions (ce que faisait autrefois le service ZooKeeper). Ici, un seul
conteneur joue les deux rôles, broker et controller.

## La démonstration à refaire

Les outils en ligne de commande sont dans le conteneur. Sous Git Bash, préfixer par
`MSYS_NO_PATHCONV=1` pour que les chemins `/opt/...` ne soient pas transformés.

```bash
K="docker exec -i backend-kafka-1 /opt/kafka/bin"; B="--bootstrap-server localhost:9092"

# 1. Un topic de 3 partitions
$K/kafka-topics.sh $B --create --topic demo-cours --partitions 3 --replication-factor 1

# 2. Des messages « ISIN:JSON » : la partie avant « : » est la clé
printf 'FR0000120321:{"cours":381.5}\nFR0000131104:{"cours":74.2}\nFR0000120321:{"cours":382.0}\n' \
  | $K/kafka-console-producer.sh $B --topic demo-cours --property parse.key=true --property key.separator=:

# 3. Lecture par le groupe « ecran », avec partition et offset
$K/kafka-console-consumer.sh $B --topic demo-cours --group ecran --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true --property print.offset=true

# 4. Où en est le groupe ? (CURRENT-OFFSET, LOG-END-OFFSET, LAG)
$K/kafka-consumer-groups.sh $B --describe --group ecran
```

Ce que l'on observe :

- les messages de L'Oréal (`FR0000120321`) sont tous dans la **même partition**, dans l'ordre ;
- après la lecture, le **lag** du groupe `ecran` est 0 ; si l'on produit 2 nouveaux messages,
  il passe à 1 sur deux partitions, et une nouvelle lecture ne renvoie **que** ces 2 messages ;
- un **nouveau groupe** (`--group scoring`) relit les 8 messages depuis le début, sans gêner
  le groupe `ecran`.

C'est exactement ce dont l'application aura besoin : l'écran en direct et le recalcul des
notes liront le même topic des cours, chacun à son rythme.

Supprimer le topic de démonstration :

```bash
$K/kafka-topics.sh $B --delete --topic demo-cours
```

## Dans l'application : le topic `quotes`

```
Yahoo (chaque minute) → MarketDataService.recordQuotes → événement QuotesUpdated
   → QuotePublisher  ── producteur ──►  topic « quotes » (clé = ISIN, 3 partitions, 1 jour)
   → QuoteStream     ◄── consommateur, groupe « quote-stream » ──  → navigateur (SSE)
```

Pour le voir sans attendre la bourse, écrire soi-même un cours dans le topic, avec le direct
ouvert dans un autre terminal (`curl -N localhost:8080/api/quotes/stream`) ou sur une fiche
société :

```bash
printf 'FR0000120321:{"isin":"FR0000120321","price":399.99,"currency":"EUR","asOf":"2026-11-07T15:30:00Z","source":"TEST","previousClose":381.5}
' \n  | $K/kafka-console-producer.sh $B --topic quotes --property parse.key=true --property key.separator=:
```

Le navigateur affiche aussitôt 399,99 € pour L'Oréal (le cours n'est pas enregistré en base :
il disparaît au rechargement de la page). Dans Kafka UI, l'onglet **Consumers** montre le
groupe `quote-stream`, sa position et son retard sur chaque partition.

## Dans l'application : le topic `filings` et l'outbox

```
Import ESEF ─► FinancialsService.recordFiling()      UNE transaction PostgreSQL
                 ├─ INSERT filing + faits
                 └─ publishEvent(FilingRecorded) ─► INSERT event_publication  (l'outbox)
                                                     │
               après le commit, Spring Modulith ─────┘ ─► topic « filings » (clé = ISIN, rétention illimitée)
                                                                    │
   module scoring : FilingListener, groupe « score-history » ◄──────┘
        └─ recalcule les 4 notes ─► INSERT … ON CONFLICT DO UPDATE  score_history
                                                                    │
   fiche société : graphique « Historique des notes » ◄─────────────┘
```

**Le problème de la double écriture.** Écrire le rapport en base *puis* dans Kafka, ce sont deux
systèmes : si l'application s'arrête entre les deux, la base a le rapport et Kafka n'en saura
jamais rien. On ne peut pas les mettre dans une même transaction.

**L'outbox.** On écrit l'événement *dans la base*, dans la même transaction que le rapport (table
`event_publication`, tenue par Spring Modulith) : les deux existent, ou aucun. Ensuite seulement,
Spring Modulith l'envoie à Kafka et note la date de fin (`completion_date`). Si l'envoi échoue,
la ligne reste sans date de fin et l'événement est renvoyé :

- au redémarrage (`spring.modulith.events.republish-outstanding-events-on-restart=true`) ;
- toutes les 5 minutes par `FilingOutboxRetry`, pour ceux qui ont plus de 2 minutes.

**Au moins une fois, donc idempotent.** Un message peut arriver deux fois (envoyé, mais Kafka a
répondu trop tard). Le consommateur l'accepte : la clé primaire de `score_history` (société,
profil, jour, origine) et `ON CONFLICT DO UPDATE` font qu'un doublon réécrit la même ligne. Le
jour vient du message (`recordedAt`), pas de l'heure de lecture : rejoué un mois plus tard, il
retombe sur la même ligne.

**Rejouer le passé.** Le topic garde tout (`retention.ms=-1`, quelques milliers de messages). Un
nouveau groupe avec `auto.offset.reset=earliest` traite tous les rapports déjà publiés : c'est ce
que fera l'analyse par le LLM local (phase 5), sans toucher à l'import.

Les deux autres sources de l'historique ne passent pas par Kafka : la **photo du soir**
(18 h 45 en semaine, `POST /api/admin/scoring/history/snapshot` pour la prendre tout de suite)
et la **reconstitution des exercices passés**, une fois au démarrage.

### Vérifier que tout fonctionne

| Où regarder | Ce qui est normal |
|---|---|
| Kafka UI → Topics → `filings` → Messages | un message par rapport importé, clé = ISIN, valeur = JSON de `FilingRecorded` |
| Kafka UI → Consumers → `score-history` | état STABLE, 1 membre, lag 0 |
| Table `event_publication` | les lignes `FilingRecorded` ont une `completion_date` |
| Logs du backend | `Historique des notes : rapport … → {QUALITY=…}` |

Les publications restées en attente (Kafka arrêté au moment de l'import) :

```sql
SELECT publication_date, completion_attempts, status
  FROM event_publication
 WHERE event_type = 'com.analyseparts.financials.FilingRecorded' AND completion_date IS NULL;
```

**L'expérience à faire** : arrêter Kafka (`docker compose stop kafka`), importer un rapport
(`POST /api/admin/ingestion/esef?isin=…`), constater la ligne sans `completion_date`, redémarrer
Kafka, attendre au plus 5 minutes : la ligne est terminée et le message est dans le topic.


## Choix de configuration (`compose.yaml`)

- **Trois listeners** (portes d'entrée) : `EXTERNAL` (9092) pour Windows et l'application,
  `INTERNAL` (19092) pour les autres conteneurs (Kafka UI), `CONTROLLER` (9093) pour Kafka
  lui-même. Chacun annonce l'adresse par laquelle le client doit le rappeler
  (`ADVERTISED_LISTENERS`) : `localhost` vu de Windows, `kafka` vu du réseau Docker.
- **Facteur de réplication 1** : un seul broker, donc une seule copie de chaque partition. En
  production on en met 3 pour survivre à la panne d'un serveur.
- **3 partitions par défaut** et **rétention 7 jours**.
