# ADR 0015 — Rapports semestriels lus en PDF, chiffres sur 12 mois glissants

- **Statut** : Acceptée
- **Date** : 2026-11-09
- **S'appuie sur** : ADR 0011 (un LLM local lit, il ne note pas)

## Contexte

Le score repose sur les comptes annuels ESEF : balisés, audités, sûrs. Mais ils vieillissent. En
novembre, les comptes 2025 ont dix mois, alors que le rapport financier semestriel au 30 juin 2026
est publié depuis l'été. Ce rapport est déposé à l'AMF (sous-type « Rapports financiers et d'audit
semestriels/examens réduits » du jeu de données `flux-amf-new-prod`), mais **en PDF, sans
balisage XBRL**. Aucun programme ne peut lire ses chiffres de façon sûre.

## Décision

1. **Recherche et téléchargement** : `AmfHalfYearDocuments` (module ingestion) liste les PDF du
   sous-type semestriel. Il écarte les simples avis de mise à disposition. On essaie au plus trois
   documents publiés après la fin du semestre attendu, c'est-à-dire six mois après la dernière
   clôture annuelle, en fin de mois.
2. **Extraction du texte** : PDFBox 3, dans l'ordre d'écriture du PDF (`sortByPosition=false`).
   Cet ordre garde chaque ligne de tableau entière. `pdftotext -layout` décalait les colonnes de
   Nexans.
3. **Page du compte de résultat** : elle est choisie par des mots-clés, sans le modèle. Le titre
   en tête de page compte triple. Les autres états (« résultat global », bilan, trésorerie,
   annexes) sont pénalisés : chez LVMH, l'état du résultat global l'emportait sinon. On garde
   deux pages au plus, et seulement si elles sont voisines.
4. **Lecture par le LLM local** (qwen3:14b, format JSON imposé) de quatre lignes : chiffre
   d'affaires, résultat opérationnel, résultat net part du groupe, BPA de base. Le modèle recopie
   **d'abord les en-têtes de colonnes**, puis les montants dans le même ordre. C'est **le code**
   qui décide quelle colonne est le semestre, le semestre précédent ou l'exercice, d'après l'année
   et le mois de chaque en-tête. Laissé seul juge, le modèle intervertissait les colonnes chez
   Air Liquide, qui met 2025 avant 2026.
5. **Vérifications**, toutes nécessaires. Chaque rejet est journalisé avec sa raison.
   - Chaque nombre figure **tel qu'écrit** sur la page.
   - Les résultats partiels sont écartés : activités poursuivies, résultat « courant », résultat
     ajusté. Chez Nexans, le BPA des activités poursuivies faussait le calcul sur 12 mois.
   - La fin du semestre tombe à 7 jours près de la date attendue.
   - La colonne « exercice complet », quand elle existe, égale les comptes annuels ESEF à 1 % près.
     Cela confirme à la fois la colonne et l'unité.
   - Le chiffre d'affaires du semestre précédent pèse 30 à 70 % de l'exercice. Ce contrôle a
     écarté un chiffre de LVMH où le modèle avait collé le numéro de note.
   - **Au moins un montant est confronté aux comptes annuels**, sinon tout est écarté, car l'unité
     du tableau ne serait pas sûre. Cas de Covivio : ses revenus locatifs n'ont pas d'équivalent
     balisé.
6. **Calcul sur 12 mois glissants** = dernier exercice + semestre − même semestre un an plus tôt.
   Le calcul n'est fait que si le semestre suit bien le dernier exercice importé. Il est affiché
   sur la page de la société, avec le détail du calcul et un lien vers le PDF.
7. **Rien de tout cela n'entre dans le score**, conformément à l'ADR 0011. Les chiffres viennent
   d'un modèle de langage, et les comptes semestriels ne font l'objet que d'un examen limité.

Table `half_year_report` (V15) : une ligne par société et par fin de semestre. Les lignes gardées
sont stockées en JSON. Endpoints : `GET /api/companies/{isin}/half-year`,
`POST /api/admin/half-year/{isin}` et `POST /api/admin/half-year/missing`.

## Conséquences

- Pour les sociétés lues, des chiffres plus récents de six mois que les comptes annuels.
- La lecture est exigeante : on préfère ne rien afficher plutôt qu'un chiffre douteux. Sont donc
  écartés :
  - les sociétés sans montant comparable aux comptes annuels (foncières à revenus locatifs) ;
  - les comptes retraités entre-temps : chez Icade, l'exercice 2025 du rapport semestriel diffère
    des comptes annuels ;
  - les tableaux dont les en-têtes n'ont pas d'année.
- Les banques ne déposent souvent pas de rapport sous ce sous-type : leur actualisation semestrielle
  passe par un amendement au document d'enregistrement universel. Elles n'ont pas de chiffres sur
  12 mois.
- Environ 15 s de GPU par société, dans une file à un seul fil, comme la lecture des rapports annuels.
