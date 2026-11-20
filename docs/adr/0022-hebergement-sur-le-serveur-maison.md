# ADR 0022 — Hébergement sur le serveur maison, base PostgreSQL à côté de l'appli

- **Statut** : Acceptée
- **Date** : 2026-11-20
- **Remplace** : l'hébergement de l'[ADR 0016](0016-demo-publique-gratuite.md) (Render + Neon) ;
  le reste de 0016 tient toujours (un seul conteneur, pas de LLM en ligne, routes admin par jeton)

## Contexte

Sur Render gratuit, le serveur s'endort après 15 minutes sans visite : le premier affichage attend
jusqu'à 2 minutes (128 s mesurées le 20 novembre). Il n'a que 0,1 processeur et 512 Mo, d'où un
cache AOT construit exprès pour démarrer à temps, et des collectes décalées en semaine de 6 h à
20 h, seules heures où cron-job.org le gardait éveillé. Neon limite la base à 512 Mo et à 100 h de
calcul par mois.

Un portable sous Ubuntu tourne en permanence à la maison (i5 2 cœurs / 4 threads, 7,6 Go), avec
Docker, Traefik et un tunnel Cloudflare déjà en place pour héberger des applis : voir la doc
« Serveur maison : connexion et hébergement d'applis web Docker ».

## Décision

- L'appli est publiée sur <https://analyse-parts.anthonypereira.dpdns.org>, selon la chaîne de
  cette doc : `git push` sur `main` → GitHub construit l'image et la publie sur `ghcr.io` → le
  runner du portable (étiquette `serveur-maison`) lance `deploy-app`.
- Une seule image, `app` (Dockerfile racine, site + API), au lieu du front/back du modèle :
  `.github/workflows/deploy.yml` et `docker-compose.yml` sont les modèles adaptés à ce point.
- La base est un PostgreSQL 18 dans le même `docker-compose.yml`, sur un réseau interne, jamais
  exposé. Copiée une fois depuis Neon (`pg_dump` / `pg_restore`).
- Le Dockerfile perd ce qui n'existait que pour Render : cache AOT (et son étape d'entraînement,
  profil `training` supprimé), compilateur C1 seul, ramasse-miettes série, tas de 300 Mo. La JVM
  prend 75 % d'une limite de 1 Go.
- Les collectes reprennent leurs horaires normaux (nuit, week-end compris) ; plus de réveil
  cron-job.org.
- `ADMIN_TOKEN` vit dans `~/apps/analyse-parts/.env` sur le portable ; le déploiement refuse de
  démarrer sans lui.

## Conséquences

- Plus d'attente au premier affichage, et 40 fois plus de processeur pour les calculs.
- Le site dépend de la maison : coupure de courant, de box ou de Wi-Fi = site hors ligne.
- Le service Render, la base Neon et le réveil cron-job.org sont à supprimer à la main.
- Le lecteur local (ADR 0019) ne peut plus écrire dans la base en ligne par Internet : la base du
  portable n'est pas exposée. Les lectures se copient par `pg_dump` des tables concernées, comme
  avant pour Neon.
