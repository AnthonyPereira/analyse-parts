# ADR 0011 — Lecture des rapports annuels par un LLM local

- **Statut** : Acceptée, remplacée en partie par [ADR 0012](0012-lecture-des-rapports-plus-complete.md) (modèle, tableaux, lots)
- **Date** : 2026-11-08
- **Complète** : [ADR 0003](0003-methode-de-score.md) (le score reste calculé sans le LLM), [ADR 0005](0005-sources-marche-et-amf.md)

## Contexte

Le score lit les **chiffres** des rapports ESEF. Leur **texte** (ce que fait la société, sa
stratégie, ses risques) reste inexploité, alors qu'un débutant en a besoin pour comprendre une
note. Le document de conception prévoit pour la phase 5 une analyse par un modèle de langage
**local** : pas de coût par requête, et aucun document envoyé à un service extérieur.

Contraintes mesurées sur la machine (RTX 4070 Ti, 12 Go) :

- un rapport annuel fait 1,2 à 1,8 million de caractères de texte, soit plus de 400 000 tokens :
  aucun modèle local ne le lit d'un coup ;
- `qwen3:14b` (9 Go) avec 16 000 tokens de contexte ne tient pas en mémoire graphique : 5 couches
  sur 41 tournent sur le processeur ; `qwen3:8b` (5 Go) tient entièrement ;
- un jeu ou une autre application qui occupe la carte graphique divise la vitesse par 10 ou plus.

## Décision

1. **Ollama**, installé sur la machine (pas dans Docker : l'accès à la carte graphique y est plus
   compliqué), appelé par **Spring AI 2.0** (`spring-ai-starter-model-ollama`).
2. **Recherche puis réponse** (RAG, *Retrieval-Augmented Generation*) :
   - le XHTML du rapport est découpé en passages de 1 000 à 1 800 caractères (`ReportText`) ;
     tableaux, en-tête caché et passages surtout numériques écartés ;
   - chaque passage reçoit un **embedding** calculé par `bge-m3` (multilingue : les rapports en
     anglais sont trouvés par des questions en français) ;
   - pour chaque thème, trois recherches formulées à l'avance choisissent 14 passages, envoyés au
     modèle avec la question.
3. **Trois thèmes** : ce que fait la société, ses avantages concurrentiels, ses principaux risques.
4. **Réponse en JSON imposé** (paramètre `format` d'Ollama, avec un JSON Schema) : chaque point
   donne un titre, une explication, le numéro de l'extrait et une citation recopiée mot pour mot.
   Température 0,1 ; mode « réflexion » de qwen3 désactivé (plus lent, pas plus fidèle).
5. **Garde-fou : chaque citation est vérifiée** dans les passages (`Quotes`). La comparaison
   tolère majuscules, espaces, apostrophes, guillemets, accents et points de suspension ; une
   citation reformulée ou un chiffre modifié est rejeté, et le point avec. Le nombre de points
   rejetés est enregistré et affiché.
6. **Jamais dans le score.** La lecture est affichée sur la fiche société, à côté de la note,
   avec le nom du modèle et un avertissement.
7. **Une lecture à la fois**, dans une file en mémoire. Déclenchée automatiquement pour chaque
   nouveau rapport annuel (le plus récent de la société), à la demande
   (`POST /api/admin/analysis/{isin}`), ou pour tous les rapports pas encore lus
   (`POST /api/admin/analysis/missing`, plusieurs heures).
8. **Stockage** : table `report_analysis` (migration V13), une ligne par rapport lu, les thèmes en
   JSONB.

## Alternatives écartées

- **Base vectorielle (pgvector)** : on ne cherche que dans un rapport à la fois (environ
  1 000 passages, moins d'une milliseconde en Java). Les embeddings sont donc recalculés à chaque
  lecture et jetés. pgvector deviendra utile pour chercher dans **tous** les rapports à la fois
  (« quelles sociétés parlent de leur dépendance à la Chine ? »).
- **Envoyer le rapport entier à un modèle à très long contexte** : impossible en local, et
  coûteux avec une API externe.
- **Modèle distant (API)** : contraire au choix du document de conception (local, gratuit, privé).
- **Laisser le LLM noter la société** : une note doit être reproductible et explicable ; celle
  d'un LLM change d'une exécution à l'autre et ne s'explique pas.
- **Réparation automatique des sources par le LLM** (proposée lors de l'ADR 0010) : une erreur du
  modèle enregistrerait de faux chiffres sans que personne ne le voie.

## Conséquences

- Une lecture prend quelques minutes, carte graphique libre (téléchargement, environ
  1 000 embeddings, trois questions). Lire toute la cote prend une nuit.
- La citation vérifiée prouve que la phrase existe dans le rapport, pas que le point en tire la
  bonne conclusion : l'interface invite à lire la citation.
- Les passages retrouvés peuvent manquer un point important : la lecture n'est pas exhaustive.
- Sans Ollama, l'application fonctionne ; la lecture échoue avec un message clair sur la fiche.
- Les tests n'appellent jamais Ollama : faux modèle dans les tests unitaires, lecture
  automatique désactivée dans les tests d'intégration (`src/test/resources/config/`).
- Modèle et taille de contexte réglables : `ANALYSIS_MODEL` (par exemple `qwen3:14b` si la
  carte graphique a plus de mémoire), `analyse-parts.analysis.context-tokens`.
