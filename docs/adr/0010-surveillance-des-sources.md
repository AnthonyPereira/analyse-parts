# ADR 0010 — Surveillance des sources externes

- **Statut** : Acceptée
- **Date** : 2026-11-07
- **Complète** : [ADR 0005](0005-sources-marche-et-amf.md), [ADR 0007](0007-cours-en-direct-et-historiques.md)

## Contexte

L'application dépend de sources qu'elle ne contrôle pas : Yahoo (cours, API non documentée), la
BCE (taux de change), l'AMF et filings.xbrl.org (rapports ESEF), Euronext (liste des actions,
fiches société) et GLEIF (LEI). Aucune ne prévient quand elle change sa façon de répondre.

En relisant le code, plusieurs pannes **silencieuses** sont apparues :

- si Yahoo renomme `regularMarketPrice`, le champ arrive vide, chaque cours est écarté par un
  filtre, et la collecte renvoie « 0 cours » **sans aucune erreur** : les cours se figent ;
- si une réponse « chart » n'a plus de bloc `meta`, un `NullPointerException` interrompt tout
  l'historique des cours (l'exception n'était pas attrapée) ;
- un rapport ESEF dont plus aucun chiffre n'est reconnu était ignoré sans trace ;
- si GLEIF change son format, chaque société devient « sans LEI » et n'est plus jamais retentée.

## Décision

1. **Chaque collecte fait son bilan** : combien d'essais (sociétés, requêtes, rapports), combien
   ont réellement donné des données exploitables, et la cause du premier échec. Le bilan part
   dans `ingestion.SourceHealth`.
2. **Trois états** selon le taux de réussite : `UP`, `DEGRADED` (sous le seuil de la source :
   80 % pour celles interrogées société par société, où quelques échecs sont normaux — 3 % des
   symboles n'ont pas de cours chez Yahoo), `DOWN` (rien n'a fonctionné).
3. **Des contrôles de forme**, là où une réponse « réussie » peut être vide de sens :
   - Yahoo : des résultats, mais aucun cours exploitable → « format probablement changé » ;
   - Yahoo « chart » sans `meta` → erreur claire, l'historique continue avec la société suivante ;
   - BCE : moins de 20 devises ou pas de dollar ;
   - Euronext : moins de 100 actions reconnues dans le fichier (colonnes déplacées) ;
   - ESEF : un rapport sans aucun chiffre IFRS ;
   - fiche Euronext : ni secteur ni nombre d'actions ;
   - GLEIF : aucun LEI trouvé sur au moins 10 sociétés.
4. **Visible à trois endroits** :
   - les logs : une ligne au **changement** d'état seulement (ERROR à la panne, INFO au
     rétablissement), pas une par minute ;
   - `/actuator/health`, indicateur `sources` ;
   - un bandeau en haut de chaque page du site (`GET /api/sources`), avec la source, le problème,
     le début de la panne et la dernière collecte réussie.
5. **Statut `DEGRADED`** plutôt que `DOWN` pour l'indicateur : l'application continue de servir
   les données déjà collectées. Un outil qui redémarre les applications `DOWN` ne doit pas la
   redémarrer pour une panne de Yahoo. Déclaré dans `application.properties` (rang entre
   `OUT_OF_SERVICE` et `UP`, code HTTP 200).

## Alternatives écartées

- **Laisser Kafka ou un autre outil « surveiller »** : Kafka ne lit pas les messages et ne voit
  jamais le format des sources (voir l'[ADR 0009](0009-retrait-de-kafka.md)). Seul le code qui
  lit une réponse sait à quoi elle devrait ressembler.
- **Enregistrer l'état en base** : inutile pour une seule instance. Après un redémarrage, une
  source est « inconnue » jusqu'à sa prochaine collecte (une minute pour Yahoo en séance, une
  semaine pour l'AMF) ; le bandeau n'affiche que les problèmes avérés.
- **Alertes par e-mail** : superflu pour un seul utilisateur ; le bandeau suffit.

## Conséquences

- Une panne se voit le jour même au lieu de passer inaperçue.
- Corriger un changement de format reste un travail manuel (souvent un nom de champ dans le
  client concerné) : la détection dit **où** et **quand**, pas **comment** réparer.
- Les seuils (80 %, 20 devises, 100 actions, 10 sociétés) sont des ordres de grandeur observés ;
  à ajuster si une source déclenche de fausses alertes.
