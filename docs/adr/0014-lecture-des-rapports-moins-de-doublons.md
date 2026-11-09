# ADR 0014 — Lecture des rapports : moins de doublons, de citations perdues et de faux sujets

- **Statut** : Acceptée
- **Date** : 2026-11-09
- **Complète** : ADR 0012 (sans la remplacer)

## Contexte

Relecture à la main des lectures de BNP Paribas (banque), Covivio (foncière), Nexans (industrie)
et L'Oréal (consommation), faites avec la méthode de l'ADR 0012. La plupart des points sont justes
et bien cités, mais cinq défauts reviennent :

1. **Doublons** : dans un même thème (« Ventes de câbles et services d'installation » et
   « Fabrication et installation de câbles » chez Nexans ; deux « Risques liés au changement
   climatique » chez L'Oréal), et d'un thème à l'autre (la même phrase « présent dans 64 pays »
   pour l'activité et pour un avantage chez BNP Paribas). Le regroupement par embeddings ne les
   voyait pas : les explications des deux points Nexans n'ont que 0,70 de proximité, moins que
   deux risques climatiques bien distincts de BNP Paribas (0,77).
2. **L'objet social des statuts** lu comme une activité (Covivio : « l'exploitation de fonds de
   commerce dans le secteur du tourisme »).
3. **Citations qui n'appuient pas leur point** (Covivio : « 42 % des sites sont à moins d'1 km
   d'une aire protégée » pour « Présence géographique ») et règles comptables présentées comme
   des avantages (L'Oréal : « méthode des flux de trésorerie actualisés »).
4. **Risques manquants** : chez L'Oréal, l'environnement politique et les droits de douane, écrits
   en toutes lettres. La description est à la fin d'un passage qui commence par le tableau de
   classement des risques : son embedding ressemble au tableau, et aucune recherche ne le remonte.
5. **Mauvais chapitre** lu comme présentation : chez BNP Paribas, l'information sectorielle des
   notes annexes (passage 1 775 sur 2 067) ; chez Nexans, le sommaire, dont le modèle tirait des
   intitulés de chapitre.

Aperam (autorité luxembourgeoise) n'avait que son rapport 2021 : filings.xbrl.org n'en connaît
pas d'autre, et l'AMF ne reçoit pas ses dépôts.

## Décision

- **Doublons** : en plus des embeddings (inchangés, seuil 0,85), deux points au titre identique
  sont un doublon, et le modèle reçoit la liste des points d'un thème pour signaler ceux qui
  décrivent la même chose. Il donne les numéros, sa raison, puis son verdict. Un risque est le
  même s'il a la même cause, pas les mêmes conséquences. Garde-fou : le modèle ne fond que des
  points déjà proches (titres ≥ 0,75 ou explications ≥ 0,80). Mesures : vrais doublons à
  0,875 / 0,70, 0,78 / 0,89 et 0,72 / 0,82 ; points distincts que le modèle disait en double à
  0,43 / 0,78 et 0,73 / 0,77.
- **Une phrase, un point** : une citation déjà retenue dans un thème n'en justifie pas un autre
  dans les thèmes suivants.
- **Nouvel essai de citation** (la piste de l'ADR 0012) : une citation introuvable ou réduite à un
  titre est redemandée une fois au modèle, avec l'extrait. Une citation chiffrée de 5 mots ou plus
  est acceptée (« Bureaux : 49 % du patrimoine de Covivio »).
- **Relecture par thème** : `ANALYSIS_REVIEW` accepte une liste de thèmes. Par défaut, l'activité
  et les avantages sont relus, pas les risques (l'ADR 0012 a montré qu'elle y retirait des risques
  réels).
- **Facteurs de risques lus d'un seul tenant** : comme la présentation du groupe, le chapitre des
  facteurs de risques est lu d'abord, en entier (14 passages), puis complété par les recherches.
  Les lots de risques demandent 7 points au lieu de 5.
- **Présentation au début du rapport** : le chapitre de présentation est cherché dans le premier
  quart du document.
- **Sommaires écartés** : un passage qui aligne au moins 6 intitulés numérotés n'est jamais lu
  (un sommaire en anglais sans numéros, comme celui d'Aperam, passe encore).
- **Recherches à éviter** : une vraie clause d'objet social et le vocabulaire des notes annexes
  (tests de perte de valeur, flux de trésorerie actualisés). Une recherche pour les banques et
  assurances (revenus par pôle, produit net bancaire, primes).
- **Paquet ESEF par adresse** : `POST /api/admin/ingestion/esef/package?isin=…&url=…` importe un
  paquet publié ailleurs (site de la société). Il doit être balisé avec le LEI de la société.

## Mesures (qwen3:14b)

| Société | Avant | Après |
|---|---|---|
| L'Oréal | les 3 divisions en double, Beauté Dermatologique absente ; pas de risque politique ni de droits de douane ; deux règles comptables en avantages | les 4 divisions (un doublon sur deux lectures sur trois : « Produits Professionnels ») ; risque politique et douanier en tête ; plus de règle comptable |
| Covivio | objet social (« tourisme ») ; citations hors sujet | ses trois métiers avec leur poids (bureaux 49 %, résidentiel allemand 30 %, hôtels 21 %) |
| Nexans | doublon câbles ; « gestion des risques géopolitiques » rangée dans l'activité | plus de doublon ; risques géopolitiques et politique commerciale américaine dans les risques |
| BNP Paribas | activité : 3 points, dont une note sur les écarts d'acquisition | 6 points (modèle diversifié, 550 agences…), dont 2 sur la gestion des risques, mal rangés |
| Aperam | rapport 2021 seulement | 2022, 2023 et 2025 importés depuis le site d'Aperam (2024 n'y est pas publié) ; noté ; rapport 2025 lu (anglais), les derniers « risques » viennent des notes comptables |

Une lecture prend 4 à 5 minutes au lieu de 3 : la recherche des doublons et le nouvel essai
ajoutent quelques appels au modèle par thème.

## Conséquences

- Moins de points, mais plus justes et sans répétition.
- L'activité des banques reste la partie la plus pauvre (2 à 4 points chez BNP Paribas) : leur
  rapport décrit les métiers par pôle dans des chapitres que les recherches trouvent mal.
- Les rapports d'Aperam ne sont pas suivis automatiquement : un nouveau rapport publié sur son site
  doit être importé à la main.
- Le risque de change de L'Oréal est encore cité depuis une note comptable : la lecture du chapitre
  des risques ne garantit pas que le modèle choisisse la meilleure phrase.
