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
| [0004](0004-baremes-par-secteur.md) | Barèmes adaptés au secteur ICB par médianes calibrées puis figées | Acceptée |
| [0005](0005-sources-marche-et-amf.md) | Sources : AMF en priorité (rapports annuels), fiche Euronext (secteur, actions), Yahoo et BCE (cours, change) | Acceptée |
| [0006](0006-front-react.md) | Front React + TypeScript (Vite), TanStack Query, React Router, CSS simple | Acceptée |
| [0007](0007-cours-en-direct-et-historiques.md) | Cours en direct par SSE (Yahoo chaque minute), historiques des cours et des ratios, graphiques Recharts | Acceptée |
| [0008](0008-kafka.md) | Kafka (KRaft) pour les événements : topic `quotes` (cours, clé ISIN) consommé par le direct ; `filings` via outbox à venir | Acceptée |
