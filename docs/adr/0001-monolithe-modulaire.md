# ADR 0001 — Monolithe modulaire (Spring Modulith) avec un cœur de scoring en Java pur

- **Statut** : Acceptée
- **Date** : 2026-11-06

## Contexte

Analyse Parts note des sociétés cotées de 0 à 100 à partir de leurs comptes. Le traitement
est une chaîne :

```
liste des actions (Euronext) → LEI (GLEIF) → rapports annuels (ESEF) → ratios → score
                                                    ↘ cours, actualités, analyse LLM
```

Contraintes qui pèsent sur l'architecture :

1. **Un seul développeur**, sur son temps libre. Chaque pièce en plus coûte en maintenance.
2. **Coût zéro** : hébergement de la démo sur Oracle Cloud Free Tier, LLM exécuté localement
   en batch. La RAM et le nombre de processus à faire tourner sont limités.
3. **Le nombre de sources va augmenter** (Euronext, GLEIF, ESEF, cours, actualités, puis
   d'autres places européennes). Chaque source doit rester isolée des autres.
4. **Objectif d'apprentissage** : Kafka (Phase 4). L'architecture doit permettre de
   l'introduire sans tout réécrire.
5. **Projet vitrine** : l'architecture doit être lisible et ses choix justifiables en entretien.
6. **Le scoring est le cœur métier** : c'est la partie la plus importante à garder simple,
   testable et indépendante du reste.

## Décision

### 1. Une seule application, découpée en modules métier

Le backend est **un seul déployable Spring Boot**, organisé en **modules par domaine**
(un package de premier niveau = un module) :

| Module | Rôle |
|---|---|
| `company` | Les sociétés cotées (ISIN, LEI, nom, pays…). Utilisé par tous les autres. |
| `ingestion` | Faire entrer les données externes. Un sous-package par source (`euronext`, `gleif`, `esef`, `prices`, `news`…). |
| `financials` | Les chiffres extraits des rapports et les ratios calculés. |
| `scoring` | Le calcul du score selon un profil, avec son explication. |
| `api` | Les endpoints REST consommés par le front React. |

### 2. Des frontières vérifiées avec Spring Modulith

[Spring Modulith](https://spring.io/projects/spring-modulith) (projet officiel Spring) :

- **vérifie les frontières dans un test** : le build échoue si un module utilise une classe
  interne d'un autre module (seules les classes du package racine d'un module sont publiques
  pour les autres) ;
- **fait communiquer les modules par événements** (`ApplicationEvent`) plutôt que par appels
  directs : l'import Euronext publie « société créée », le module GLEIF y réagit, sans que
  les deux se connaissent ;
- **enregistre les événements en base** (*event publication registry*) : un événement non
  traité (crash, erreur) est rejoué au redémarrage ;
- **peut externaliser ces événements vers Kafka** (`@Externalized`) : en Phase 4, on branche
  Kafka sur des événements qui existent déjà, sans réécrire les services ;
- **génère la documentation des modules** (diagrammes C4 / PlantUML).

### 3. Le module `scoring` est écrit en Java pur (style hexagonal, localement)

Le cœur du scoring (ratios → notes → score pondéré par le profil → explication) ne dépend
**ni de Spring, ni de JPA, ni de HTTP**. Il reçoit des objets Java simples et en renvoie.
Les accès à la base et le déclenchement se font autour de lui, dans des classes Spring fines.

Le reste de l'application (ingestion, API) garde une structure Spring classique
(contrôleur → service → repository, clients HTTP) : y imposer des ports et adaptateurs
partout ajouterait des interfaces sans bénéfice réel.

## Alternatives écartées

### A. Architecture en couches (`controllers/`, `services/`, `repositories/`, `entities/`)

La structure la plus répandue dans les tutoriels Spring.

- **Pour** : connue de tous, rien à apprendre, parfaite pour un petit CRUD.
- **Pourquoi écartée** : le code d'une même source se retrouverait dispersé dans 4 dossiers
  (le client Euronext dans `clients/`, son service dans `services/`, etc.). Avec 6 ou 7 sources
  prévues, chaque dossier deviendrait un fourre-tout et rien n'empêcherait un service d'appeler
  n'importe quel autre. Le découpage technique ne dit rien de ce que fait l'application.

### B. Package par fonctionnalité, sans vérification

C'est l'état du projet avant cette décision.

- **Pour** : bon rangement, aucune dépendance en plus.
- **Pourquoi pas suffisant** : les frontières ne reposent que sur la discipline. Rien
  n'empêche `ingestion` d'aller lire une classe interne de `scoring`, et ces raccourcis
  s'accumulent sans qu'on s'en rende compte. Spring Modulith ajoute la vérification par-dessus
  ce rangement sans le changer : c'est une évolution, pas un remplacement.

### C. Architecture hexagonale (ports & adapters / Clean Architecture) partout

Le domaine au centre, chaque accès externe (base, HTTP, Kafka) derrière une interface
(« port ») avec son implémentation (« adaptateur »).

- **Pour** : domaine totalement indépendant de la technique, très testable, technologies
  remplaçables.
- **Pourquoi écartée en tant que règle générale** : une grande partie du projet *est* de la
  technique (télécharger un CSV, appeler GLEIF, parser du XBRL). Pour ces parties, l'interface
  et son unique implémentation doublent le nombre de classes sans rien apporter : on ne changera
  pas de source GLEIF, et les tests utilisent déjà de faux serveurs HTTP
  (`MockRestServiceServer`) ou des mocks.
- **Ce qu'on en garde** : l'idée de fond, appliquée **là où elle paie**, c'est-à-dire au
  scoring (cf. décision n°3).

### D. Microservices (un service déployé par domaine)

Par exemple `ingestion-service`, `scoring-service`, `api-gateway`, reliés par Kafka.

- **Pour** : déploiement et montée en charge indépendants, isolation forte, technologies
  différentes possibles par service. Pertinent pour plusieurs équipes et un fort trafic.
- **Pourquoi écartée** :
  - **un seul développeur** : plusieurs dépôts ou pipelines, versions de contrats entre
    services, débogage distribué, tout ça sans équipe pour le porter ;
  - **coût** : chaque service est une JVM (plusieurs centaines de Mo), avec souvent sa propre
    base. Sur une offre gratuite, la RAM serait vite épuisée ;
  - **aucun besoin réel** : pas de trafic à absorber, pas d'équipes à rendre indépendantes.
    L'ingestion tourne en batch, le scoring se calcule en millisecondes ;
  - **cohérence des données** : le score dépend des sociétés, des ratios et des profils. Les
    répartir dans plusieurs bases obligerait à gérer de la cohérence à terme, là où une
    transaction suffit aujourd'hui.
- **Ce qu'on en garde** : la communication par événements entre modules. Si un jour un module
  doit vraiment devenir un service (voir « Quand revoir »), ses frontières et ses événements
  existeront déjà : le découper sera une opération de déploiement, pas une réécriture.

### E. Kafka dès maintenant, comme bus entre les modules

- **Pour** : on apprend Kafka plus tôt.
- **Pourquoi écartée pour l'instant** : on paierait la complexité de Kafka (broker à faire
  tourner, sérialisation, gestion des offsets et des rejeux) avant d'avoir une chaîne qui
  fonctionne de bout en bout. Les événements Spring Modulith donnent le même découplage
  aujourd'hui, et Kafka arrive en Phase 4 comme prévu dans la feuille de route.

## Conséquences

**Positives**
- Un seul processus à lancer, déboguer et déployer ; une seule base, des transactions simples.
- Frontières entre modules vérifiées automatiquement à chaque build.
- Passage à Kafka en Phase 4 sans réécriture des services.
- Scoring testable en quelques millisecondes, lisible sans connaître Spring.
- Diagrammes d'architecture générés, utiles pour la documentation et les entretiens.

**Négatives / à surveiller**
- Une dépendance de plus (Spring Modulith), dont il faut suivre la compatibilité avec
  Spring Boot 4.
- Un peu de discipline : décider à chaque nouvelle classe si elle est publique pour les
  autres modules (package racine) ou interne (sous-package).
- Les événements rendent l'enchaînement moins visible qu'un appel direct : il faut les nommer
  clairement et les documenter.
- Tout le backend se déploie et monte en charge d'un bloc (acceptable vu le besoin).

## Quand revoir cette décision

- Un module a besoin de **ressources très différentes** des autres. Exemple : l'analyse LLM
  sur GPU, qui tourne déjà à part en batch local. C'est le premier candidat si on veut un jour
  sortir un service.
- Plusieurs personnes travaillent sur le projet et se gênent sur le même déployable.
- La charge réelle impose de faire monter en charge une seule partie (par exemple l'API
  publique) indépendamment du reste.
