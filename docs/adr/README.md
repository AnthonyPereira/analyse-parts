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
| [0008](0008-kafka.md) | Kafka (KRaft) pour les événements : topics `quotes` et `filings` (outbox) | Remplacée par 0009 |
| [0009](0009-retrait-de-kafka.md) | Retrait de Kafka (inutile pour une instance et un utilisateur) ; historique des notes gardé, par événement Spring Modulith | Acceptée |
| [0010](0010-surveillance-des-sources.md) | Surveillance des sources externes : bilan de chaque collecte, statut DEGRADED, bandeau sur le site | Acceptée |
| [0011](0011-lecture-des-rapports-par-llm-local.md) | Lecture des rapports annuels par un LLM local (Ollama, qwen3:8b) : recherche par embeddings, citations vérifiées, jamais dans le score | Remplacée en partie par 0012 et 0017 |
| [0012](0012-lecture-des-rapports-plus-complete.md) | Lecture plus complète : qwen3:14b, tableaux rédigés gardés, plusieurs lots par thème, doublons regroupés, chapitre de présentation, relecture réglable | Acceptée |
| [0013](0013-notation-des-assurances.md) | Notation à part pour les assurances (ICB 3030) : poids sans endettement ni cash-flow, comme pour les banques | Acceptée |
| [0014](0014-lecture-des-rapports-moins-de-doublons.md) | Lecture des rapports : doublons signalés par le modèle, nouvel essai de citation, facteurs de risques lus d'un seul tenant, sommaires écartés, relecture par thème, import d'un paquet ESEF par adresse | Acceptée |
| [0015](0015-rapports-semestriels-et-12-mois-glissants.md) | Rapports semestriels PDF lus par le LLM local, colonnes reconnues par leurs en-têtes, vérifiés contre les comptes annuels ; chiffres sur 12 mois glissants affichés, hors score | Acceptée |
| [0016](0016-demo-publique-gratuite.md) | Démo publique gratuite : un conteneur sur Render, PostgreSQL chez Neon, sans LLM ; routes admin protégées par jeton, éveil en semaine de 6 h à 20 h | Acceptée |
| [0017](0017-embeddings-snowflake-arctic-embed2.md) | Embeddings : snowflake-arctic-embed2, recherches précédées de « query: » (18 % de passages utiles en plus que bge-m3, mesuré sur 7 rapports) | Acceptée |
