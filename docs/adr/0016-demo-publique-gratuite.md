# ADR 0016 — Démo publique gratuite : Render + Neon, sans LLM

- **Statut** : Acceptée
- **Date** : 2026-11-10
- **Remplace** : l'hébergement sur Oracle Cloud Free Tier prévu dans le document de conception

## Contexte

On veut mettre en ligne un MVP : le classement, les fiches sociétés, les cours. La lecture des
rapports par le LLM local est mise en pause. Contraintes : gratuit, et **sans carte bancaire**.
Oracle Cloud Free Tier en demande une, tout comme Fly.io, Google Cloud Run et Koyeb. Aucune offre
gratuite et sans carte ne garde un serveur allumé en permanence.

## Décision

1. **Un seul conteneur** (`Dockerfile` à la racine) : le site React est compilé puis copié dans
   `static/` du backend, et Spring sert l'API et le site à la même adresse. Pas de CORS, un seul
   service à déployer. `SpaForwardController` renvoie `index.html` pour les routes du site.
2. **Render, offre gratuite** pour le conteneur (512 Mo, HTTPS et adresse `*.onrender.com`
   fournis). Le serveur s'endort après 15 min sans visite et met environ une minute à repartir.
3. **Neon, offre gratuite** pour PostgreSQL (1 Go, sans date de fin). La base gratuite de Render
   est supprimée au bout de 30 jours. Les données sont copiées depuis la base locale (`pg_dump`) :
   pas de nouvelle collecte complète au démarrage, et les lectures LLM déjà faites restent
   affichées.
4. **Profil `prod`** (`application-prod.properties`) : pas de lecture automatique par le LLM, pas
   de reconstitution de l'historique au démarrage, petit pool de connexions, santé sans détail.
5. **Routes `/api/admin/**` protégées** par un jeton (`ADMIN_TOKEN`, en-tête `X-Admin-Token`,
   `AdminTokenFilter`). Vide en local : rien ne change pour le développement.
6. **Éveil en semaine de 6 h à 20 h (heure de Paris)** : cron-job.org appelle
   `/actuator/health/liveness` toutes les 10 min. Cette route ne touche pas la base. Les collectes
   de nuit (AMF, fiches Euronext, ESEF) sont décalées vers 7 h. Environ 300 h d'éveil par mois,
   sous les 750 h de Render et les 100 h·CU de Neon. GitHub Actions n'a pas été retenu : le dépôt
   est privé, et un ping toutes les 10 min userait presque toutes les 2 000 minutes gratuites.

7. **Démarrage rapide malgré 0,1 processeur** : Render arrête le service s'il n'ouvre pas son port
   en 3 minutes environ, et le premier démarrage a pris 202 s. L'image utilise donc le cache AOT
   de Java (JEP 483/514), entraîné pendant la construction sur un PostgreSQL temporaire (profil
   `training`), avec le jar extrait et le compilateur C1 seul. Mesuré en local avec 0,1 processeur :
   le démarrage passe de 122 s à 19 s.

## Conséquences

- Le soir et le week-end, la première visite attend environ une minute. Les cours restent ceux
  de la dernière séance, ce qui est normal puisque la Bourse est fermée.
- 512 Mo de mémoire suffisent de justesse pour la JVM (`-XX:MaxRAMPercentage=60`,
  `-XX:+UseSerialGC`). Si le serveur manque de mémoire, il faudra changer d'hébergeur.
- Pas de nouvelle lecture par le LLM en ligne. Pour en ajouter, on les lance en local, puis on
  recopie la base.
- La base gratuite est limitée à 1 Go. Le 10 novembre 2026, la base locale pèse 83 Mo (cours
  37 Mo, faits ESEF 34 Mo).
