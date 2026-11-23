# ADR 0023 — Lectures faites sur le PC, copiées vers le serveur maison par son runner

- **Statut** : Acceptée
- **Date** : 2026-11-23
- **Remplace** : la cible de l'[ADR 0019](0019-lecteur-local-branche-sur-la-base-en-ligne.md)
  (le lecteur écrivait dans Neon) ; le reste de 0019 tient toujours (profil `reader`, le PC avec
  Ollama lit, le serveur n'a pas de modèle de langage)

## Contexte

Depuis l'[ADR 0022](0022-hebergement-sur-le-serveur-maison.md), la base de production est un
PostgreSQL sur le réseau interne du compose du portable : pas de port ouvert, mot de passe
seulement dans le `.env` du serveur. Le lecteur ne peut donc plus s'y brancher comme il le
faisait sur Neon. Pendant ce temps, il a lu plus de 900 rapports dans la base locale du PC.

Options écartées :

- ouvrir le port de la base sur le réseau de la maison : une base de production joignable par
  toute machine du réseau, et un mot de passe à recopier sur le PC ;
- une route d'administration qui reçoit les lectures : du code serveur en plus, et le jeton
  d'administration à garder sur le PC ;
- un tunnel SSH : pas de clé de ce PC vers le portable.

## Décision

- Le lecteur (`scripts/lecteur.ps1`) écrit dans la base **locale**, lecture par lecture.
- `scripts/publier-lectures.ps1` exporte toutes les lectures locales en CSV compressé, avec
  l'ISIN à la place de l'identifiant interne de la société (les deux bases ont vécu séparément),
  les dépose dans une release temporaire du dépôt privé, lance le workflow
  `.github/workflows/lectures.yml`, attend sa fin et supprime la release.
- Le workflow tourne sur le runner du portable (`serveur-maison`), dans le même groupe de
  concurrence que le déploiement : il télécharge le fichier, le copie dans le conteneur `db` et
  lance `scripts/importer-lectures.sql` par `docker exec` (socket local, sans mot de passe).
- L'import est idempotent : une lecture déjà présente (même société, même rapport) n'est remplacée
  que si celle du PC est plus récente, ou classée par importance alors que celle du serveur ne
  l'est pas. Une société inconnue du serveur est comptée et ignorée.

## Conséquences

- Les lectures arrivent sur le site quand on lance la publication, pas au fil de l'eau ; on publie
  après une session de lecture.
- Le fichier transite quelques minutes par GitHub (dépôt privé) : des lectures de rapports publics,
  rien de personnel.
- Tout le fichier est renvoyé à chaque publication (environ 6 Mo compressés pour 1 200 lectures) :
  plus simple que de demander au serveur ce qu'il a déjà, et sans effet grâce à l'import idempotent.
- `NEON_URL` / `NEON_DIRECT_URL` du `.env` ne servent plus.
