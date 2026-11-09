# ADR 0013 — Une notation à part pour les assurances

- **Statut** : Acceptée
- **Date** : 2026-11-09

## Contexte

Les banques (ICB 3010) ont leurs propres poids depuis le 7 novembre. Les trois assurances cotées
à Paris (AXA, SCOR, Coface, super-secteur ICB 3030) restaient notées comme des industriels :

- leur bilan est présenté par ordre de liquidité (IAS 1) : pas de liquidité générale ni de dette
  nette au sens industriel ;
- leur résultat ne passe pas par un « résultat opérationnel » et un cash-flow libre comparables ;
- conséquence : AXA n'avait pas de note Value (45 % des poids calculables, sous le seuil de 60 %)
  et sa note Qualité tenait à 60 % des poids.

Les indicateurs du métier, ratio combiné et ratio de solvabilité (Solvabilité II), ne sont pas
balisés dans les rapports ESEF.

## Décision

Chaque profil a un troisième jeu de poids, `Profile.insurerWeights`, appliqué au super-secteur
3030. Il ne garde que les ratios qui ont un sens pour un assureur : ROE, ROA, marge nette,
exercices bénéficiaires, croissance (chiffre d'affaires et BPA), dividende, PER, cours / valeur
comptable. Ni ratios d'endettement ou de cash-flow, ni ratios bancaires. Le détail des poids est
dans `docs/ratios.md`.

`Profile.Book` (STANDARD, BANK, INSURER) remplace le booléen « banque ».

## Conséquences

- AXA, SCOR et Coface ont une note dans les quatre profils.
- Le barème sectoriel (ADR 0004) s'applique toujours : le super-secteur Assurance garde sa propre
  médiane.
- Sans ratio combiné ni solvabilité, la note ne dit rien de la prudence de souscription : limite
  écrite dans le lexique (fiche « Assurances »).
