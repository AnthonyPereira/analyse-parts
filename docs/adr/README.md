# Décisions d'architecture (ADR)

Un ADR (*Architecture Decision Record*) note **une** décision importante : le contexte,
le choix fait, les alternatives écartées et leurs conséquences. Il sert à se souvenir
*pourquoi* le code est comme il est, et à savoir quand une décision mérite d'être revue.

Règles :
- un fichier par décision, numéroté : `NNNN-titre-court.md` ;
- on ne réécrit pas un ADR accepté : si la décision change, on en écrit un nouveau
  qui **remplace** l'ancien (et on met à jour le statut de l'ancien).

| N° | Décision | Statut |
|---|---|---|
| [0001](0001-monolithe-modulaire.md) | Monolithe modulaire (Spring Modulith) + cœur de scoring en Java pur | Acceptée |
| [0002](0002-stockage-donnees-financieres.md) | Stocker les faits XBRL bruts, calculer les indicateurs à la lecture | Acceptée |
| [0003](0003-methode-de-score.md) | Score par barèmes absolus, pondérés par profil ; ratio manquant retiré, jamais inventé | Acceptée |
