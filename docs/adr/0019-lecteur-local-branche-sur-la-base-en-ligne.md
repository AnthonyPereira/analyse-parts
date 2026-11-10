# ADR 0019 — Lecteur local branché sur la base en ligne

- **Statut** : Acceptée
- **Date** : 2026-11-10
- **Complète** : [ADR 0016](0016-demo-publique-gratuite.md) (démo gratuite, sans LLM en ligne)

## Contexte

Le serveur en ligne (Render, 512 Mo, 0,1 processeur) collecte chaque matin les nouveaux rapports,
mais ne peut pas les lire : le modèle de langage tourne sur la carte graphique du PC (Ollama).
Jusqu'ici, on lisait en local, dans la base locale, puis on copiait les lectures vers Neon à la
main. 179 sociétés avaient un dernier rapport sans lecture.

## Décision

Un mode « lecteur » de la même application (profil `reader`, `application-reader.properties`),
lancé sur le PC par `scripts/lecteur.ps1` :

- il se branche sur la base en ligne (adresse directe `NEON_DIRECT_URL` du fichier `.env`) ;
- toutes les 30 min, si sa file est vide, une requête trouve les sociétés dont le dernier rapport
  n'est pas lu (`FinancialsService.findLatestFilings`, une seule requête pour toutes) ; il les lit
  une par une et enregistre les lectures, classées par importance (ADR 0018) ;
- il ne fait rien d'autre : ni site, ni tâches planifiées (`analyse-parts.scheduling.enabled=false`,
  un interrupteur unique pour toutes), ni migration de la base (Flyway coupé : le schéma appartient
  au serveur en ligne), ni événements rejoués, ni calcul des notes au démarrage ;
- une société dont la lecture échoue n'est pas reprise avant le prochain démarrage.

**Le quota de Neon** (100 h·CU par mois) compte le temps où la base est éveillée ; elle s'endort
après 5 min sans activité. Une lecture dure environ 5 min : écrire après chacune la garderait
éveillée toute la nuit. Le lecteur ne garde donc aucune connexion ouverte (`minimum-idle=0`) et
écrit les lectures par paquets de 6 (`analyse-parts.analysis.save-batch`), soit une écriture par
demi-heure environ ; le dernier paquet est écrit quand la file se vide, ou à l'arrêt.

## Conséquences

- Plus de copie à la main : le PC lit quand on le lance (la nuit, par exemple), la démo affiche
  les lectures au fil de l'eau.
- Un arrêt brutal (coupure, processus tué) perd le paquet en cours : au plus 6 lectures, relues au
  lancement suivant. Ctrl+C, lui, écrit le paquet avant de quitter.
- Déployer avant de lancer le lecteur : avec une version de code plus récente que la base, il
  échouerait au démarrage (validation du schéma) au lieu de la migrer.
- La carte graphique est partagée : un jeu en cours repousse une partie du modèle en mémoire
  vive, et une lecture passe de 5 à plus de 10 minutes. Lancer le lecteur quand le PC est libre.
