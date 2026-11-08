// Le lexique de l'application : chaque terme affiché à l'écran, expliqué pour quelqu'un qui
// découvre la bourse. Il alimente les bulles d'aide (<Term>) et la page /lexique.
//
// Les identifiants des indicateurs (ROE, PER…) et des postes comptables (REVENUE…) sont ceux
// de l'API : on peut donc retrouver une définition directement depuis une donnée.

export type Category =
  | 'Le score'
  | 'Rentabilité'
  | 'Solidité financière'
  | 'Qualité du cash'
  | 'Croissance'
  | 'Régularité'
  | 'Dividende'
  | 'Valorisation'
  | 'Comptes annuels'
  | 'Bourse'
  | 'La société'
  | 'Lire les graphiques'

export const CATEGORIES: { id: Category; intro: string }[] = [
  { id: 'Le score', intro: 'Comment l\'application transforme des comptes en une note sur 100.' },
  { id: 'Rentabilité', intro: 'L\'entreprise gagne-t-elle bien sa vie ?' },
  { id: 'Solidité financière', intro: 'Peut-elle encaisser un coup dur sans risquer la faillite ?' },
  { id: 'Qualité du cash', intro: 'Ses bénéfices sont-ils du vrai argent, encaissé sur un compte en banque ?' },
  { id: 'Croissance', intro: 'Grandit-elle d\'une année sur l\'autre ?' },
  { id: 'Régularité', intro: 'Ses résultats sont-ils fiables dans le temps ?' },
  { id: 'Dividende', intro: 'Partage-t-elle ses bénéfices avec ses actionnaires, et peut-elle continuer ?' },
  { id: 'Valorisation', intro: 'L\'action est-elle chère ou bon marché par rapport à ce que l\'entreprise gagne ?' },
  { id: 'Comptes annuels', intro: 'Les chiffres bruts publiés chaque année dans le rapport annuel.' },
  { id: 'Bourse', intro: 'Le cours, la cotation, et d\'où viennent les prix affichés.' },
  { id: 'La société', intro: 'Les codes et classements qui identifient une entreprise cotée.' },
  { id: 'Lire les graphiques', intro: 'Ce que montrent les graphiques et les petits mots statistiques.' },
]

export interface Entry {
  /** Le terme tel qu'on le lit à l'écran. */
  term: string
  category: Category
  /** Une ou deux phrases : ce que montre la bulle d'aide. */
  short: string
  /** L'explication complète, en mots simples. */
  what?: string
  /** Le calcul, en toutes lettres. */
  formula?: string
  /** Comment lire la valeur : qu'est-ce qui est bon ou mauvais ? */
  read?: string
  example?: string
  /** Le piège à connaître. */
  watch?: string
  /** Termes liés (identifiants du lexique). */
  see?: string[]
}

export const GLOSSARY: Record<string, Entry> = {
  // ---------------------------------------------------------------- Le score
  SCORE: {
    term: 'Score', category: 'Le score',
    short: 'Une note sur 100 qui résume à quel point la société correspond au profil choisi (Qualité, Croissance, Dividende ou Value).',
    what: 'L\'application calcule une vingtaine d\'indicateurs à partir des comptes officiels de la société. Chacun est transformé en points de 0 à 100 grâce à un barème, puis le profil décide de l\'importance de chacun (son poids). Le score est la moyenne de ces points, pondérée par les poids.',
    formula: 'Score = somme des (points de l\'indicateur × poids de l\'indicateur) ÷ somme des poids disponibles.',
    read: 'En vert à partir de 60 : la société coche bien les cases du profil. En orange entre 40 et 59 : moyen. En rouge sous 40 : elle ne correspond pas à ce profil. « — » : pas assez de données pour noter.',
    example: 'Une société avec 80 points en ROE (poids 20) et 50 points en marge (poids 10) a (80 × 20 + 50 × 10) ÷ 30 = 70.',
    watch: 'Un score n\'est pas un conseil d\'achat. Il dit si l\'entreprise ressemble au profil, pas si l\'action va monter. Une même société peut avoir 85 en Qualité et 20 en Value : excellente, mais chère.',
    see: ['PROFILE', 'POINTS', 'WEIGHT', 'COVERAGE'],
  },
  PROFILE: {
    term: 'Profil', category: 'Le score',
    short: 'Une façon de choisir ses actions. Chaque profil donne plus ou moins d\'importance à chaque indicateur.',
    what: 'Il n\'existe pas de « meilleure action » dans l\'absolu : tout dépend de ce que l\'on cherche. Un investisseur qui veut un revenu régulier ne regarde pas les mêmes chiffres que celui qui cherche une entreprise en forte croissance. L\'application propose 4 profils classiques, et le mode avancé permet de créer le sien.',
    see: ['QUALITY', 'GROWTH', 'DIVIDEND', 'VALUE', 'WEIGHT', 'BANK'],
  },
  QUALITY: {
    term: 'Qualité', category: 'Le score',
    short: 'Profil qui cherche des entreprises très rentables, peu endettées, dont les bénéfices sont encaissés en argent réel.',
    what: 'L\'idée (popularisée par Warren Buffett) : une entreprise qui gagne beaucoup d\'argent avec peu de capital, année après année, a souvent un avantage durable sur ses concurrents (marque, brevet, position dominante). On la garde longtemps.',
    read: 'Indicateurs les plus importants : ROE (20 %), marge opérationnelle (15 %), puis marge nette, endettement, conversion en cash, cash-flow libre et régularité.',
    watch: 'Les belles entreprises sont souvent chères : regardez aussi leur note Value.',
    see: ['ROE', 'OPERATING_MARGIN', 'CASH_CONVERSION'],
  },
  GROWTH: {
    term: 'Croissance', category: 'Le score',
    short: 'Profil qui cherche des entreprises dont les ventes et les bénéfices augmentent vite, sans s\'endetter pour y arriver.',
    what: 'On parie sur l\'avenir : une entreprise qui double ses ventes en quelques années peut valoir beaucoup plus demain.',
    read: 'Indicateurs les plus importants : croissance du chiffre d\'affaires (35 %) et du bénéfice par action (30 %), puis ROE, marge et endettement.',
    watch: 'La croissance passée ne garantit pas la croissance future, et ces actions chutent fort quand la croissance ralentit.',
    see: ['REVENUE_GROWTH', 'EPS_GROWTH', 'CAGR'],
  },
  DIVIDEND: {
    term: 'Dividende', category: 'Le score',
    short: 'Profil qui cherche des entreprises versant un dividende régulier, en hausse, et qu\'elles peuvent se permettre.',
    what: 'Pour toucher un revenu chaque année, comme un loyer. Le profil ne regarde pas seulement le montant : il vérifie que l\'entreprise gagne assez d\'argent pour continuer à payer.',
    read: 'Indicateurs : rendement (20 %), taux de distribution (20 %), croissance des dividendes (20 %), puis endettement, cash et régularité.',
    watch: 'Un rendement très élevé est souvent un signal d\'alerte : le marché pense que le dividende va baisser.',
    see: ['DIVIDEND_YIELD', 'PAYOUT_RATIO', 'DIVIDEND_GROWTH'],
  },
  VALUE: {
    term: 'Value', category: 'Le score',
    short: 'Profil qui cherche des actions bon marché par rapport à ce que l\'entreprise gagne, sans dette excessive.',
    what: 'Acheter 1 € de valeur pour 60 centimes, en pariant que le marché finira par corriger son prix. « Value » est le mot anglais utilisé par tous les investisseurs.',
    read: 'Indicateurs : PER (25 %), valeur d\'entreprise / résultat opérationnel (25 %), rendement du cash-flow libre (20 %), puis cours / valeur comptable, rendement du dividende et endettement.',
    watch: 'Le « piège à value » : une action est parfois bon marché parce que l\'entreprise va mal. C\'est pour cela que le profil tient compte de la dette.',
    see: ['PER', 'EV_TO_EBIT', 'FCF_YIELD'],
  },
  FAMILY: {
    term: 'Famille d\'indicateurs', category: 'Le score',
    short: 'Les indicateurs sont rangés par question : rentabilité, solidité, cash, croissance, régularité, dividende, valorisation.',
    what: 'La note d\'une famille est la moyenne des points de ses indicateurs. Elle montre d\'un coup d\'œil les points forts et faibles : par exemple, très rentable mais endettée.',
  },
  POINTS: {
    term: 'Points', category: 'Le score',
    short: 'La note de 0 à 100 d\'un indicateur, donnée par son barème.',
    what: 'Un ROE de 15 % et un PER de 15 ne se comparent pas directement. Chaque valeur est donc traduite en points sur une même échelle : 100 = excellent, 0 = très mauvais.',
    see: ['SCALE'],
  },
  SCALE: {
    term: 'Barème', category: 'Le score',
    short: 'La règle qui transforme la valeur d\'un indicateur en points de 0 à 100.',
    what: 'Le barème fixe quelques repères ; entre deux repères, les points augmentent régulièrement (en ligne droite). Au-delà du dernier repère, on reste au maximum ou au minimum.',
    example: 'Marge nette : 0 % → 0 point, 5 % → 40, 10 % → 70, 20 % → 100. Une marge de 7,5 % (à mi-chemin entre 5 et 10 %) vaut 55 points.',
    read: 'Sur la fiche d\'une société, cliquez sur un indicateur pour voir la courbe de son barème et le point de la société dessus.',
    see: ['SECTOR_ADJUSTMENT'],
  },
  SECTOR_ADJUSTMENT: {
    term: 'Barème adapté au secteur', category: 'Le score',
    short: 'Le barème est étiré ou resserré selon le métier : une marge de 4 % est faible pour un éditeur de logiciels mais bonne pour un supermarché.',
    what: 'Pour les indicateurs dont le niveau « normal » dépend du métier (marges, endettement, PER…), on compare la médiane du secteur à celle de tout le marché, et on multiplie les repères du barème par ce rapport. Une société est ainsi jugée face à ses vrais concurrents.',
    formula: 'Facteur = médiane du secteur ÷ médiane du marché (limité entre 0,4 et 2,5). Chaque repère du barème est multiplié par ce facteur.',
    example: 'Distribution : marge nette médiane 2,4 % contre 6 % pour le marché, facteur 0,4. Le repère « 10 % → 70 points » devient « 4 % → 70 points ».',
    watch: 'Le ROE, la croissance et les rendements ne sont jamais adaptés : ils se comparent entre tous les secteurs.',
    see: ['MEDIAN', 'ICB'],
  },
  WEIGHT: {
    term: 'Poids', category: 'Le score',
    short: 'L\'importance d\'un indicateur dans le score, en pourcentage. Le total fait 100 %.',
    what: 'Chaque profil fixe les poids. Si un indicateur ne peut pas être calculé, son poids est réparti sur les autres : c\'est pourquoi la colonne Poids d\'une société peut différer de celui du profil.',
    example: 'Dans le profil Qualité, le ROE pèse 20 % : il compte deux fois plus que la marge nette (10 %).',
  },
  CONTRIBUTION: {
    term: 'Apport', category: 'Le score',
    short: 'Ce que l\'indicateur apporte au score : ses points multipliés par son poids. La somme des apports donne le score.',
    example: '72 points × 18 % = 13 points apportés au score.',
  },
  COVERAGE: {
    term: 'Données (couverture)', category: 'Le score',
    short: 'La part des indicateurs du profil que l\'on a pu calculer, en tenant compte de leur poids. Sous 60 %, pas de score.',
    what: 'Il manque parfois une information : une société qui ne publie pas ses investissements de façon standard, un cours trop ancien, une perte qui rend le PER impossible… Plus la couverture est haute, plus le score est fiable.',
    read: '100 % : tout est calculé. Entre 60 et 99 % : score calculé sans certains indicateurs (listés sous « Non pris en compte »). Sous 60 % : pas de score.',
  },
  MISSING: {
    term: 'Non pris en compte', category: 'Le score',
    short: 'Les indicateurs du profil qu\'on n\'a pas pu calculer pour cette société, avec la raison. Leur poids est réparti sur les autres.',
  },
  FRESHNESS: {
    term: 'Fraîcheur', category: 'Le score',
    short: 'Les chiffres sont-ils récents ? Une société publie ses comptes au plus tard 4 mois après la fin de son exercice.',
    read: 'À jour : exercice clos il y a moins de 16 mois. En retard : un rapport plus récent devrait exister mais n\'est pas encore importé (score calculé, mais à prendre avec prudence). Obsolète : plus de 28 mois, pas de score.',
    see: ['UP_TO_DATE', 'LATE', 'OBSOLETE', 'FISCAL_YEAR'],
  },
  UP_TO_DATE: {
    term: 'À jour', category: 'Le score',
    short: 'Exercice clos il y a moins de 16 mois : on a le rapport annuel le plus récent qui puisse exister.',
  },
  LATE: {
    term: 'En retard', category: 'Le score',
    short: 'Un rapport annuel plus récent devrait exister mais n\'est pas encore importé. Le score est calculé sur l\'avant-dernier exercice.',
  },
  OBSOLETE: {
    term: 'Obsolète', category: 'Le score',
    short: 'Les derniers comptes ont plus de 28 mois : trop anciens pour noter la société.',
  },
  SCORE_HISTORY: {
    term: 'Historique des notes', category: 'Le score',
    short: 'Les notes de la société dans les 4 profils, jour après jour et exercice après exercice : on voit si elle s\'améliore ou se dégrade.',
    what: 'L\'application garde une photo des 4 notes à trois moments : chaque soir de bourse, à l\'arrivée de chaque nouveau rapport annuel, et, pour le passé, à la clôture de chaque exercice (notes reconstituées avec les chiffres et le cours de l\'époque).',
    read: 'Une courbe par profil, de 0 à 100. Celle du profil choisi dans les onglets est mise en avant. Les gros points sont les notes reconstituées à la clôture d\'un exercice ; les traits verticaux en pointillés, l\'arrivée d\'un rapport. Entre deux rapports, c\'est surtout la note Value qui bouge : elle suit le cours.',
    watch: 'Les notes reconstituées utilisent les médianes sectorielles d\'aujourd\'hui : une petite différence avec la note réellement affichée à l\'époque est normale.',
    see: ['SCORE_ORIGIN', 'SCORE', 'FISCAL_YEAR'],
  },
  SCORE_ORIGIN: {
    term: 'Origine d\'une note', category: 'Le score',
    short: 'Pourquoi une note a été enregistrée ce jour-là : un nouveau rapport, la photo du soir, ou une reconstitution du passé.',
    what: '« Nouveau rapport » : la note recalculée dès l\'enregistrement d\'un rapport annuel. « Photo du soir » : la note de chaque soir de bourse, après la clôture. « Clôture d\'exercice » : la note qu\'aurait eue la société le dernier jour d\'un exercice passé, avec ses comptes et son cours de ce jour-là.',
    see: ['SCORE_HISTORY'],
  },
  FISCAL_YEAR: {
    term: 'Exercice', category: 'Le score',
    short: 'La période de 12 mois couverte par un rapport annuel. La date affichée est sa date de clôture (souvent le 31 décembre).',
    what: 'Certaines sociétés clôturent à une autre date (31 mars, 30 juin…). Le rapport est publié quelques semaines à 4 mois après la clôture.',
  },
  ADVANCED: {
    term: 'Mode avancé', category: 'Le score',
    short: 'Créez votre propre profil en réglant l\'importance de chaque indicateur.',
    what: 'Les barèmes, l\'adaptation au secteur et les règles de fraîcheur restent les mêmes : seuls les poids changent. Seules les proportions comptent (20 et 10 pèsent comme 2 et 1). Le réglage est enregistré dans l\'adresse de la page : vous pouvez le partager par un lien.',
  },

  // ---------------------------------------------------------------- Rentabilité
  NET_MARGIN: {
    term: 'Marge nette', category: 'Rentabilité',
    short: 'Sur 100 € de ventes, ce qu\'il reste en bénéfice une fois tout payé : fournisseurs, salaires, intérêts, impôt.',
    formula: 'Résultat net part du groupe ÷ chiffre d\'affaires.',
    read: 'Plus c\'est haut, mieux c\'est. 10 % est bon dans la plupart des métiers ; la distribution vit avec 1 à 3 %, le luxe avec 20 à 30 %.',
    example: 'Hermès garde environ 28 € sur 100 € de ventes, un supermarché 1 à 2 €.',
    watch: 'Le résultat net inclut des événements exceptionnels (vente d\'une filiale, dépréciation) qui peuvent le gonfler ou l\'écraser une année donnée.',
    see: ['NET_INCOME_GROUP', 'REVENUE', 'OPERATING_MARGIN'],
  },
  OPERATING_MARGIN: {
    term: 'Marge opérationnelle', category: 'Rentabilité',
    short: 'Sur 100 € de ventes, ce que rapporte le métier lui-même, avant les intérêts de la dette et l\'impôt.',
    formula: 'Résultat opérationnel ÷ chiffre d\'affaires.',
    read: 'Plus c\'est haut, mieux c\'est. Une marge élevée et stable signale souvent un avantage concurrentiel : l\'entreprise peut vendre bien plus cher que ce que ses produits lui coûtent.',
    what: 'Contrairement à la marge nette, elle ne dépend pas de la façon dont l\'entreprise est financée (dette ou actionnaires) ni de son pays d\'imposition : c\'est la meilleure mesure de la qualité du métier.',
    see: ['OPERATING_INCOME', 'NET_MARGIN'],
  },
  ROE: {
    term: 'ROE (rentabilité des capitaux propres)', category: 'Rentabilité',
    short: 'Ce que rapporte chaque euro que les actionnaires ont laissé dans l\'entreprise. ROE de 15 % : 100 € rapportent 15 € par an.',
    formula: 'Résultat net part du groupe ÷ capitaux propres part du groupe (moyenne du début et de la fin de l\'exercice).',
    read: 'Au-dessus de 15 % : très bon. Entre 8 et 15 % : correct. Sous 8 % : l\'argent des actionnaires rapporte peu, guère plus qu\'un placement sans risque.',
    what: 'ROE vient de l\'anglais « Return On Equity ». On divise par la moyenne des capitaux propres sur l\'année, car le bénéfice a été gagné tout au long de l\'année.',
    watch: 'La dette gonfle le ROE : avec moins d\'argent des actionnaires pour la même activité, le ratio monte. Regardez toujours l\'endettement à côté.',
    see: ['EQUITY', 'NET_DEBT_TO_EQUITY', 'ROA'],
  },
  ROA: {
    term: 'ROA (rentabilité de l\'actif)', category: 'Rentabilité',
    short: 'Ce que rapporte chaque euro investi dans l\'entreprise, qu\'il vienne des actionnaires ou des prêteurs.',
    formula: 'Résultat net ÷ total du bilan (moyenne du début et de la fin de l\'exercice).',
    read: 'Plus c\'est haut, mieux c\'est. Au-delà de 10 % : excellent. Pour une banque, qui travaille avec énormément d\'argent emprunté, 1 % est déjà bon.',
    what: 'ROA vient de l\'anglais « Return On Assets ». Contrairement au ROE, il ne peut pas être gonflé par la dette.',
    see: ['TOTAL_ASSETS', 'ROE'],
  },

  COST_INCOME_RATIO: {
    term: 'Coefficient d\'exploitation (banques)', category: 'Rentabilité',
    short: 'Banques : sur 100 € de produit net bancaire, combien partent en frais de fonctionnement (salaires, agences, informatique).',
    formula: 'Frais de fonctionnement ÷ produit net bancaire, soit 1 − résultat brut d\'exploitation ÷ produit net bancaire.',
    read: 'Plus il est bas, plus la banque est efficace. Sous 55 % : très efficace. 60-65 % : dans la moyenne des grandes banques françaises. Au-delà de 70 % : la banque coûte cher à faire tourner.',
    example: 'Crédit Agricole SA : 56 %. Sur 100 € gagnés grâce à son métier, 56 € paient son fonctionnement et 44 € restent pour couvrir les prêts non remboursés, l\'impôt et les actionnaires.',
    what: 'C\'est l\'équivalent bancaire de la marge, lu à l\'envers : une banque n\'a pas de « chiffre d\'affaires » au sens habituel, mais un produit net bancaire.',
    see: ['NET_BANKING_INCOME', 'GROSS_OPERATING_INCOME', 'BANK'],
  },

  // ---------------------------------------------------------------- Solidité
  COST_OF_RISK_RATIO: {
    term: 'Coût du risque (banques)', category: 'Solidité financière',
    short: 'Banques : sur 100 € de produit net bancaire, combien sont mis de côté pour les prêts qui ne seront pas remboursés.',
    formula: 'Coût du risque ÷ produit net bancaire. Si la banque ne publie pas son coût du risque, il se déduit : résultat brut d\'exploitation − résultat d\'exploitation.',
    read: 'Plus il est bas, plus les emprunteurs de la banque sont solides. Sous 8 % : sain. Au-delà de 15-20 % : beaucoup de clients n\'arrivent plus à rembourser, souvent en période de crise.',
    what: 'Pour une banque, la dette n\'est pas un danger en soi : c\'est sa matière première. Son vrai risque, ce sont les prêts qu\'elle a accordés et qui ne seront pas remboursés. C\'est ce que mesure cet indicateur, et c\'est pourquoi il remplace l\'endettement dans les profils des banques.',
    see: ['COST_OF_RISK', 'NET_BANKING_INCOME', 'BANK'],
  },
  NET_DEBT_TO_EQUITY: {
    term: 'Dette nette / capitaux propres (gearing)', category: 'Solidité financière',
    short: 'Ce que l\'entreprise doit vraiment (dettes moins trésorerie), comparé à ce qui appartient aux actionnaires.',
    formula: '(Dettes financières à long terme + à court terme − trésorerie) ÷ capitaux propres.',
    read: 'Plus c\'est bas, mieux c\'est. Négatif : l\'entreprise a plus d\'argent en banque que de dettes. Sous 0,5x : confortable. Au-delà de 1x : la dette dépasse l\'argent des actionnaires, une mauvaise année devient dangereuse.',
    example: '0,3x : pour 100 € appartenant aux actionnaires, l\'entreprise doit 30 € net aux banques.',
    watch: 'Le « x » signifie « fois » : 0,3x = 0,3 fois les capitaux propres.',
    see: ['NET_DEBT', 'EQUITY', 'CASH', 'MULTIPLE'],
  },
  EQUITY_RATIO: {
    term: 'Autonomie financière', category: 'Solidité financière',
    short: 'La part de l\'entreprise financée par ses actionnaires plutôt que par des dettes.',
    formula: 'Capitaux propres ÷ total du bilan.',
    read: 'Plus c\'est haut, plus l\'entreprise est solide. Au-dessus de 50 % : très solide. Sous 20 % : elle dépend beaucoup de ses créanciers.',
    see: ['EQUITY', 'TOTAL_ASSETS'],
  },
  CURRENT_RATIO: {
    term: 'Liquidité générale', category: 'Solidité financière',
    short: 'Ce qui va rentrer en argent sous un an, comparé à ce qu\'il faudra payer sous un an.',
    formula: 'Actifs courants (stocks, créances clients, trésorerie) ÷ passifs courants (fournisseurs, dettes à moins d\'un an).',
    read: 'Au-dessus de 1,5x : à l\'aise. Sous 1x : l\'entreprise a besoin de ses banques ou de ses clients pour payer ses factures du moment.',
    watch: 'N\'a pas de sens pour les banques et les assurances, qui ne séparent pas court et long terme : il est retiré de leur calcul.',
  },

  // ---------------------------------------------------------------- Cash
  CASH_CONVERSION: {
    term: 'Conversion du résultat en cash', category: 'Qualité du cash',
    short: 'L\'argent réellement encaissé par l\'activité, comparé au bénéfice comptable. Proche de 1 ou plus : le bénéfice est du vrai argent.',
    formula: 'Flux de trésorerie d\'exploitation ÷ résultat net.',
    what: 'Le bénéfice est une construction comptable : une vente compte dès qu\'elle est facturée, même si le client n\'a pas encore payé. Le flux de trésorerie, lui, ne compte que l\'argent arrivé sur le compte.',
    read: 'Au-dessus de 1x : très bon signe. Durablement sous 0,8x : les clients paient mal, les stocks gonflent… ou les comptes sont trop optimistes.',
    see: ['OPERATING_CASH_FLOW', 'NET_INCOME_GROUP'],
  },
  FCF_MARGIN: {
    term: 'Marge de cash-flow libre', category: 'Qualité du cash',
    short: 'Sur 100 € de ventes, l\'argent qui reste une fois l\'activité payée et les investissements faits.',
    formula: '(Flux de trésorerie d\'exploitation − investissements) ÷ chiffre d\'affaires.',
    read: 'Plus c\'est haut, mieux c\'est. Au-delà de 10 % : excellent.',
    what: 'C\'est cet argent-là, pas le bénéfice, qui paie les dividendes, rembourse les dettes ou rachète des actions.',
    see: ['FCF', 'CAPEX'],
  },

  // ---------------------------------------------------------------- Croissance
  REVENUE_GROWTH: {
    term: 'Croissance annuelle du chiffre d\'affaires', category: 'Croissance',
    short: 'De combien les ventes ont augmenté en moyenne chaque année sur les exercices disponibles.',
    formula: 'Taux de croissance annuel moyen (CAGR) du chiffre d\'affaires, sur au moins 3 exercices.',
    read: 'Au-delà de 10 % par an : forte croissance. Autour de 0 : stagnation. Négatif : les ventes baissent.',
    see: ['CAGR', 'REVENUE'],
  },
  EPS_GROWTH: {
    term: 'Croissance annuelle du bénéfice par action', category: 'Croissance',
    short: 'De combien le bénéfice revenant à chaque action a augmenté en moyenne chaque année.',
    formula: 'Taux de croissance annuel moyen (CAGR) du bénéfice par action.',
    what: 'On suit le bénéfice par action plutôt que le bénéfice total : si l\'entreprise crée beaucoup d\'actions nouvelles, le bénéfice total peut monter alors que la part de chaque actionnaire baisse.',
    watch: 'Impossible à calculer si l\'entreprise était en perte au début ou à la fin de la période.',
    see: ['CAGR', 'EPS_BASIC'],
  },
  CAGR: {
    term: 'Taux de croissance annuel moyen (CAGR)', category: 'Croissance',
    short: 'La croissance « lissée » : le pourcentage constant qui, appliqué chaque année, mène du premier au dernier chiffre.',
    formula: '(valeur finale ÷ valeur initiale) ^ (1 ÷ nombre d\'années) − 1.',
    example: 'Passer de 100 à 121 en deux ans, c\'est +10 % par an (100 × 1,10 × 1,10 = 121), et non +10,5 %.',
    what: 'CAGR vient de l\'anglais « Compound Annual Growth Rate ».',
  },

  // ---------------------------------------------------------------- Régularité
  PROFITABLE_YEARS: {
    term: 'Exercices bénéficiaires', category: 'Régularité',
    short: 'La part des exercices disponibles terminés par un bénéfice.',
    read: '100 % : jamais de perte sur la période. Une entreprise de qualité gagne de l\'argent chaque année, même les mauvaises.',
  },

  // ---------------------------------------------------------------- Dividende
  PAYOUT_RATIO: {
    term: 'Taux de distribution', category: 'Dividende',
    short: 'La part du bénéfice reversée aux actionnaires en dividendes.',
    formula: 'Dividendes versés ÷ résultat net part du groupe.',
    read: 'Idéal entre 40 et 60 % : un vrai revenu pour l\'actionnaire, et de quoi investir pour l\'avenir. Au-delà de 100 %, l\'entreprise distribue plus qu\'elle ne gagne : elle puise dans ses réserves ou s\'endette, ce qui ne peut pas durer.',
    watch: 'C\'est le seul barème « en cloche » : trop bas comme trop haut font perdre des points. Si l\'exercice est en perte et qu\'un dividende est quand même versé, le taux devient négatif (dividende ÷ perte) : 0 point, car ce dividende n\'est couvert par aucun bénéfice.',
    see: ['DIVIDENDS_PAID'],
  },
  DIVIDEND_GROWTH: {
    term: 'Croissance annuelle des dividendes versés', category: 'Dividende',
    short: 'De combien le montant total des dividendes versés a augmenté en moyenne chaque année.',
    read: 'Positif et régulier : l\'entreprise partage une part croissante de sa réussite. Négatif : le dividende a été réduit.',
    see: ['CAGR', 'DIVIDENDS_PAID'],
  },
  DIVIDEND_YIELD: {
    term: 'Rendement du dividende', category: 'Dividende',
    short: 'Ce que rapporterait chaque année une action achetée au cours du jour, si le dividende se maintient.',
    formula: 'Dividendes versés sur le dernier exercice ÷ capitalisation boursière. Si ce versement est plus de 2 fois supérieur à la médiane des exercices précédents (dividende exceptionnel après une cession, par exemple), on retient cette médiane : le dividende habituel.',
    read: 'Entre 3 et 5 % : rendement confortable. Au-delà de 7-8 % : méfiance, le marché anticipe souvent une baisse du dividende.',
    example: 'Une action à 100 € qui verse 4 € par an rend 4 %.',
    watch: 'Le rendement monte quand le cours baisse : un rendement élevé peut cacher une entreprise en difficulté. Le calcul regarde le dividende déjà versé : si l\'entreprise vient de faire une perte, il risque de baisser (regardez alors le taux de distribution).',
    see: ['MARKET_CAP', 'PAYOUT_RATIO'],
  },

  // ---------------------------------------------------------------- Valorisation
  PER: {
    term: 'PER (cours / bénéfice par action)', category: 'Valorisation',
    short: 'Combien de fois le bénéfice annuel on paie l\'action. PER de 15 : le prix équivaut à 15 ans de bénéfices actuels.',
    formula: 'Cours de l\'action ÷ bénéfice par action (ou capitalisation ÷ résultat net).',
    read: 'Plus il est bas, moins l\'action est chère. Autour de 10 : bon marché. 15 à 20 : dans la moyenne. Au-delà de 30 : le marché paie cher une croissance future.',
    what: 'PER vient de l\'anglais « Price Earnings Ratio ». C\'est le ratio de valorisation le plus connu.',
    watch: 'Un PER très bas peut signaler une entreprise en déclin : c\'est le « piège à value ». Impossible à calculer en cas de perte.',
    see: ['EPS_BASIC', 'EV_TO_EBIT'],
  },
  EV_TO_EBIT: {
    term: 'Valeur d\'entreprise / résultat opérationnel', category: 'Valorisation',
    short: 'Le prix de toute l\'entreprise (ses actions et sa dette nette) rapporté à ce que rapporte son métier.',
    formula: '(Capitalisation boursière + dette nette) ÷ résultat opérationnel.',
    read: 'Plus il est bas, moins c\'est cher. Sous 8x : bon marché. Au-delà de 18x : cher.',
    what: 'La « valeur d\'entreprise » est ce qu\'il faudrait payer pour racheter l\'entreprise entière : toutes ses actions, plus ses dettes à reprendre, moins sa trésorerie. Avantage sur le PER : deux entreprises identiques, l\'une endettée et l\'autre non, ont le même ratio. C\'est le préféré des analystes. On l\'écrit souvent « VE / EBIT » (EBIT = résultat opérationnel en anglais).',
    see: ['MARKET_CAP', 'NET_DEBT', 'OPERATING_INCOME', 'PER'],
  },
  FCF_YIELD: {
    term: 'Rendement du cash-flow libre', category: 'Valorisation',
    short: 'Si vous achetiez toute l\'entreprise au cours du jour, le pourcentage de votre mise qu\'elle vous rapporterait chaque année en argent réel.',
    formula: 'Cash-flow libre ÷ capitalisation boursière.',
    read: 'Plus c\'est haut, moins l\'action est chère. Au-delà de 6 % : attractif. À comparer au taux d\'un livret ou d\'une obligation.',
    see: ['FCF', 'MARKET_CAP'],
  },
  PRICE_TO_BOOK: {
    term: 'Cours / valeur comptable', category: 'Valorisation',
    short: 'Combien le marché paie pour 1 € de capitaux propres inscrits dans les comptes.',
    formula: 'Capitalisation boursière ÷ capitaux propres.',
    read: 'Sous 1x : la bourse valorise l\'entreprise moins que ce que disent ses comptes. Au-delà de 3x : le marché paie surtout ce qui n\'est pas au bilan (marque, savoir-faire).',
    watch: 'Un éditeur de logiciels ou une marque de luxe a peu d\'actifs comptables : son ratio est naturellement élevé, d\'où l\'adaptation au secteur.',
    see: ['EQUITY', 'MARKET_CAP'],
  },

  // ---------------------------------------------------------------- Comptes annuels
  ANNUAL_REPORT: {
    term: 'Rapport annuel (ESEF)', category: 'Comptes annuels',
    short: 'Le document officiel où une société cotée publie ses comptes chaque année. L\'application lit sa version numérique standard (ESEF).',
    what: 'Depuis 2021, les sociétés cotées en Europe publient leur rapport annuel au format ESEF : une page web dans laquelle chaque chiffre porte une étiquette lisible par un ordinateur (XBRL). L\'application récupère ces rapports auprès de l\'AMF (le gendarme de la bourse français) et de filings.xbrl.org.',
    see: ['INCOME_STATEMENT', 'BALANCE_SHEET', 'CASH_FLOW_STATEMENT'],
  },
  INCOME_STATEMENT: {
    term: 'Compte de résultat', category: 'Comptes annuels',
    short: 'Ce que l\'entreprise a gagné et dépensé pendant l\'exercice : du chiffre d\'affaires jusqu\'au bénéfice.',
  },
  BALANCE_SHEET: {
    term: 'Bilan', category: 'Comptes annuels',
    short: 'Une photo, à la date de clôture, de ce que l\'entreprise possède (actif) et de ce qu\'elle doit (passif).',
    what: 'Les deux côtés sont toujours égaux : tout ce que l\'entreprise possède a été financé soit par ses actionnaires (capitaux propres), soit par des dettes.',
  },
  CASH_FLOW_STATEMENT: {
    term: 'Flux de trésorerie', category: 'Comptes annuels',
    short: 'L\'argent réellement entré et sorti pendant l\'exercice, sans les conventions comptables.',
  },
  REVENUE: {
    term: 'Chiffre d\'affaires', category: 'Comptes annuels',
    short: 'Le total des ventes de l\'année. C\'est la première ligne du compte de résultat.',
  },
  NET_BANKING_INCOME: {
    term: 'Produit net bancaire (PNB)', category: 'Comptes annuels',
    short: 'Le « chiffre d\'affaires » d\'une banque : ce qu\'elle gagne avec son métier, avant ses frais de fonctionnement.',
    formula: 'Intérêts reçus sur les prêts − intérêts versés sur les dépôts et emprunts + commissions + gains sur les marchés.',
    what: 'Une banque ne vend pas de produits : elle prête de l\'argent plus cher qu\'elle ne l\'emprunte, et facture des services. Le PNB additionne ces marges. Pour les banques, l\'application l\'utilise à la place du chiffre d\'affaires (marge nette, croissance).',
    see: ['BANK', 'COST_INCOME_RATIO'],
  },
  GROSS_OPERATING_INCOME: {
    term: 'Résultat brut d\'exploitation (banques)', category: 'Comptes annuels',
    short: 'Banques : le produit net bancaire moins les frais de fonctionnement, avant les pertes sur les prêts.',
  },
  COST_OF_RISK: {
    term: 'Coût du risque', category: 'Comptes annuels',
    short: 'Banques : l\'argent mis de côté sur l\'année pour les prêts que des clients ne rembourseront pas.',
    see: ['COST_OF_RISK_RATIO'],
  },
  DIVIDENDS_PER_SHARE: {
    term: 'Dividende par action versé', category: 'Comptes annuels',
    short: 'Le dividende payé pour une action pendant l\'exercice. Quand le montant total n\'est pas publié, l\'application le retrouve en multipliant ce dividende par le nombre d\'actions.',
  },
  GROSS_PROFIT: { term: 'Marge brute', category: 'Comptes annuels',
    short: 'Le chiffre d\'affaires moins le coût direct des produits vendus.' },
  OPERATING_INCOME: {
    term: 'Résultat opérationnel', category: 'Comptes annuels',
    short: 'Ce que rapporte le métier : les ventes moins toutes les dépenses de fonctionnement, avant intérêts de la dette et impôt. En anglais : EBIT.',
  },
  PROFIT_BEFORE_TAX: { term: 'Résultat avant impôt', category: 'Comptes annuels',
    short: 'Le résultat opérationnel, après les frais financiers, mais avant l\'impôt sur les bénéfices.' },
  INCOME_TAX: { term: 'Impôt sur les bénéfices', category: 'Comptes annuels',
    short: 'L\'impôt payé sur le bénéfice de l\'année.' },
  NET_INCOME: {
    term: 'Résultat net', category: 'Comptes annuels',
    short: 'Le bénéfice final, une fois tout payé. Négatif : c\'est une perte.',
  },
  NET_INCOME_GROUP: {
    term: 'Résultat net part du groupe', category: 'Comptes annuels',
    short: 'Le bénéfice qui revient aux actionnaires de la société, sans la part des actionnaires minoritaires de ses filiales.',
    what: 'Quand une société détient 80 % d\'une filiale, 20 % du bénéfice de cette filiale revient à d\'autres actionnaires. La « part du groupe » les retire.',
  },
  EPS_BASIC: {
    term: 'Bénéfice par action (BPA)', category: 'Comptes annuels',
    short: 'Le résultat net part du groupe divisé par le nombre d\'actions : le bénéfice qui revient à une action. En anglais : EPS.',
  },
  EPS_DILUTED: { term: 'Bénéfice par action dilué', category: 'Comptes annuels',
    short: 'Le bénéfice par action si toutes les actions promises (stock-options, obligations convertibles) étaient créées.' },
  TOTAL_ASSETS: {
    term: 'Total du bilan', category: 'Comptes annuels',
    short: 'La valeur de tout ce que possède l\'entreprise : usines, stocks, créances, trésorerie, marques achetées…',
  },
  CURRENT_ASSETS: { term: 'Actif courant', category: 'Comptes annuels',
    short: 'Ce qui doit se transformer en argent dans l\'année : stocks, créances clients, trésorerie.' },
  CURRENT_LIABILITIES: { term: 'Passif courant', category: 'Comptes annuels',
    short: 'Ce qu\'il faut payer dans l\'année : fournisseurs, salaires, impôts, dettes à moins d\'un an.' },
  EQUITY: {
    term: 'Capitaux propres', category: 'Comptes annuels',
    short: 'L\'argent des actionnaires dans l\'entreprise : ce qu\'ils ont apporté, plus les bénéfices qu\'ils y ont laissés.',
    what: 'C\'est aussi ce qui resterait aux actionnaires si l\'entreprise vendait tout ce qu\'elle possède au prix des comptes et remboursait toutes ses dettes.',
  },
  EQUITY_GROUP: { term: 'Capitaux propres part du groupe', category: 'Comptes annuels',
    short: 'Les capitaux propres qui reviennent aux actionnaires de la société, sans la part des minoritaires des filiales.' },
  CASH: {
    term: 'Trésorerie', category: 'Comptes annuels',
    short: 'L\'argent disponible : comptes en banque et placements récupérables très vite.',
  },
  INVENTORIES: { term: 'Stocks', category: 'Comptes annuels',
    short: 'Les marchandises, matières premières et produits pas encore vendus.' },
  GOODWILL: { term: 'Écarts d\'acquisition (goodwill)', category: 'Comptes annuels',
    short: 'Quand une entreprise en rachète une autre plus cher que sa valeur comptable, la différence est inscrite au bilan sous ce nom.' },
  LONG_TERM_DEBT: {
    term: 'Dettes financières à long terme', category: 'Comptes annuels',
    short: 'Les emprunts bancaires et obligations à rembourser dans plus d\'un an.',
  },
  SHORT_TERM_DEBT: {
    term: 'Dettes financières à court terme', category: 'Comptes annuels',
    short: 'Les emprunts à rembourser dans l\'année.',
  },
  NET_DEBT: {
    term: 'Dette nette', category: 'Comptes annuels',
    short: 'Les dettes financières moins la trésorerie : ce que l\'entreprise devrait encore si elle utilisait tout son argent pour rembourser.',
    formula: 'Dettes financières à long terme + à court terme − trésorerie.',
  },
  OPERATING_CASH_FLOW: {
    term: 'Flux de trésorerie d\'exploitation', category: 'Comptes annuels',
    short: 'L\'argent réellement encaissé grâce à l\'activité pendant l\'année (« cash d\'exploitation »).',
  },
  CAPEX: {
    term: 'Investissements (capex)', category: 'Comptes annuels',
    short: 'L\'argent dépensé pour acheter ou renouveler usines, machines, magasins, logiciels. « Capex » vient de l\'anglais capital expenditure.',
  },
  FCF: {
    term: 'Cash-flow libre', category: 'Comptes annuels',
    short: 'L\'argent qui reste une fois l\'activité financée et les investissements faits. En anglais : free cash flow (FCF).',
    formula: 'Flux de trésorerie d\'exploitation − investissements.',
    what: 'C\'est la mesure la plus honnête de ce que gagne une entreprise : c\'est avec cet argent qu\'elle paie ses dividendes, rembourse ses dettes ou rachète ses actions.',
  },
  DIVIDENDS_PAID: {
    term: 'Dividendes versés', category: 'Comptes annuels',
    short: 'Le montant total versé aux actionnaires pendant l\'année, pris dans le tableau des flux de trésorerie.',
  },

  // ---------------------------------------------------------------- Bourse
  SOURCE_STATUS: {
    term: 'Source de données en panne', category: 'Bourse',
    short: 'L\'application va chercher ses données chez d\'autres : Yahoo (cours), la BCE (taux de change), l\'AMF et filings.xbrl.org (rapports annuels), Euronext (liste des actions, secteurs) et GLEIF (identifiants LEI). Quand l\'une d\'elles ne répond plus ou change sa façon de répondre, un bandeau le signale.',
    what: 'Après chaque collecte, l\'application compare ce qu\'elle a demandé et ce qu\'elle a réellement pu lire. « En panne » : rien n\'a pu être lu (source injoignable, ou format de réponse changé). « Partiellement en échec » : une partie seulement, moins que d\'habitude. Quelques échecs isolés sont normaux (un titre suspendu n\'a pas de cours, par exemple) et ne déclenchent rien.',
    read: 'Pendant une panne, l\'application continue d\'afficher les dernières données collectées : un cours en panne reste figé à sa dernière valeur, un rapport annuel publié pendant la panne arrivera plus tard. Les notes restent calculées, mais peuvent être en retard.',
    watch: 'Un changement de format chez une source demande une correction dans l\'application : le bandeau reste affiché jusqu\'à ce qu\'une collecte réussisse à nouveau.',
    see: ['QUOTE', 'ANNUAL_REPORT'],
  },
  REPORT_ANALYSIS: {
    term: 'Lecture du rapport par une IA locale', category: 'Comptes annuels',
    short: 'Un modèle de langage (une IA qui lit et écrit du texte) lit le rapport annuel de la société et résume ce qu\'elle fait, ses avantages face aux concurrents et ses principaux risques. Il tourne sur l\'ordinateur qui héberge l\'application : le rapport n\'est envoyé à aucun service extérieur.',
    what: 'Un rapport annuel fait souvent plus de 300 pages, trop pour que le modèle le lise d\'un coup. L\'application le découpe en passages, retrouve pour chaque thème la quinzaine de passages qui en parlent (recherche par le sens, pas par mots-clés), et pose la question au modèle avec ces seuls passages. Il doit appuyer chaque point sur une phrase recopiée du rapport.',
    read: 'Chaque point se déplie pour montrer la phrase du rapport qui l\'appuie, et le passage d\'où elle vient. C\'est la société qui parle d\'elle-même : ses avantages sont ceux qu\'elle revendique, ses risques ceux qu\'elle choisit de présenter.',
    watch: 'Le modèle peut mal interpréter une phrase exacte, ou oublier un point important qui n\'était pas dans les passages retrouvés. Cette lecture aide à comprendre la société ; elle n\'entre pas dans le score et ne remplace pas la lecture du rapport.',
    see: ['VERIFIED_QUOTE', 'ANNUAL_REPORT'],
  },
  VERIFIED_QUOTE: {
    term: 'Citation vérifiée', category: 'Comptes annuels',
    short: 'Chaque point écrit par l\'IA doit citer une phrase du rapport. Avant d\'afficher le point, l\'application vérifie que cette phrase figure vraiment dans le rapport ; sinon, le point est écarté.',
    what: 'Un modèle de langage peut « halluciner » : écrire une phrase plausible mais fausse. La vérification compare la citation au texte du rapport en ignorant seulement ce qui ne change pas le sens (majuscules, espaces, apostrophes, guillemets). Une citation reformulée ou un chiffre modifié ne passe pas.',
    read: 'Le nombre de points écartés est indiqué sous la lecture. Beaucoup de points écartés signale un modèle qui a du mal avec ce rapport : à lire avec d\'autant plus de prudence.',
    watch: 'La vérification prouve que la phrase existe, pas que le point en tire la bonne conclusion : lisez la citation.',
    see: ['REPORT_ANALYSIS'],
  },
  QUOTE: {
    term: 'Cours', category: 'Bourse',
    short: 'Le prix d\'une action à un instant donné, fixé par les achats et les ventes en bourse.',
  },
  DELAYED: {
    term: 'Cours différé', category: 'Bourse',
    short: 'Le cours vient de Yahoo Finance avec environ 15 minutes de retard. Le cours en temps réel est payant.',
    what: 'Le serveur interroge Yahoo chaque minute pendant la séance (9 h à 17 h 30) et envoie chaque nouveau cours à votre navigateur dès qu\'il arrive : le prix clignote en vert s\'il monte, en rouge s\'il baisse.',
    see: ['LIVE'],
  },
  LIVE: {
    term: 'Direct', category: 'Bourse',
    short: 'Le point vert indique que votre navigateur est connecté au serveur : les cours se mettent à jour tout seuls, sans recharger la page.',
  },
  PREVIOUS_CLOSE: {
    term: 'Clôture de la veille', category: 'Bourse',
    short: 'Le dernier cours de la séance précédente. La variation du jour (▲ ou ▼) est calculée par rapport à lui.',
  },
  DAILY_CHANGE: {
    term: 'Variation du jour', category: 'Bourse',
    short: 'De combien le cours a monté (▲, en vert) ou baissé (▼, en rouge) depuis la clôture de la veille, en pourcentage.',
  },
  MARKET_CAP: {
    term: 'Capitalisation boursière', category: 'Bourse',
    short: 'Le prix de l\'entreprise entière en bourse : cours de l\'action × nombre d\'actions.',
    example: '400 € × 500 millions d\'actions = 200 milliards d\'euros.',
  },
  EURONEXT_PARIS: {
    term: 'Cotation (Euronext Paris)', category: 'Bourse',
    short: 'La bourse où l\'action s\'échange. Euronext Paris est la bourse française.',
  },

  // ---------------------------------------------------------------- La société
  ISIN: {
    term: 'ISIN', category: 'La société',
    short: 'Le code unique d\'une action dans le monde, comme une plaque d\'immatriculation. Il commence par le code du pays (FR pour la France).',
    example: 'FR0000121014 est l\'action LVMH.',
  },
  LEI: {
    term: 'LEI', category: 'La société',
    short: 'Le code unique d\'une entreprise (et non de son action) dans les registres officiels. Il sert à retrouver ses rapports annuels.',
  },
  TICKER: {
    term: 'Mnémonique (ticker)', category: 'La société',
    short: 'L\'abréviation de l\'action en bourse, en quelques lettres.',
    example: 'MC pour LVMH, AI pour Air Liquide.',
  },
  ICB: {
    term: 'Secteur (ICB)', category: 'La société',
    short: 'Le classement international des entreprises par métier, utilisé par Euronext. Il a 4 niveaux, du plus large au plus précis.',
    what: 'Industrie (11, ex. « Consumer Discretionary ») › super-secteur (20, ex. « Consumer Products and Services ») › secteur › sous-secteur. L\'application compare les sociétés au sein de leur super-secteur. Les noms restent en anglais, comme dans la classification officielle.',
    see: ['SUPERSECTOR'],
  },
  BANK: {
    term: 'Banques : une notation à part', category: 'La société',
    short: 'Les banques ne se lisent pas comme les autres entreprises. Chaque profil les note avec des indicateurs propres à leur métier.',
    what: 'Une banque prête l\'argent de ses déposants : plus de 90 % de son bilan est financé par des dettes, et c\'est normal. L\'endettement, la liquidité, la valeur d\'entreprise et le cash-flow libre n\'ont donc pas de sens pour elle. Elle n\'a pas non plus de chiffre d\'affaires, mais un produit net bancaire. Pour une banque, chaque profil pose les mêmes questions (est-elle rentable ? solide ? bon marché ?) avec les indicateurs que regardent les analystes du secteur : ROE, coefficient d\'exploitation, coût du risque, PER et cours / valeur comptable.',
    watch: 'Les ratios de solvabilité réglementaires (CET1) ne figurent pas dans les rapports numériques standard : ils ne sont pas encore pris en compte. Les assurances sont pour l\'instant notées comme les autres entreprises.',
    see: ['COST_INCOME_RATIO', 'COST_OF_RISK_RATIO', 'NET_BANKING_INCOME', 'CCI'],
  },
  CCI: {
    term: 'Certificats coopératifs (CCI)', category: 'La société',
    short: 'Les caisses régionales du Crédit Agricole ne sont cotées qu\'à travers leurs certificats coopératifs d\'investissement, une petite partie de leur capital.',
    what: 'Le reste du capital appartient aux sociétaires (les clients) et à Crédit Agricole SA. Le cours multiplié par le nombre de certificats ne vaut donc pas la caisse entière : le comparer à son bénéfice total donnerait un PER ou un rendement absurdes (jusqu\'à 24 % de rendement). L\'application ne calcule pas leurs ratios de valorisation : elles n\'ont pas de note Value.',
  },
  SUPERSECTOR: {
    term: 'Super-secteur', category: 'La société',
    short: 'Le deuxième niveau de la classification ICB (20 au total). C\'est le groupe de concurrents auquel on compare une société.',
  },

  // ---------------------------------------------------------------- Graphiques
  MEDIAN: {
    term: 'Médiane', category: 'Lire les graphiques',
    short: 'La valeur du milieu : la moitié des sociétés fait mieux, l\'autre moitié moins bien.',
    what: 'On préfère la médiane à la moyenne, car une seule société aux chiffres extrêmes ne la fausse pas.',
    example: 'Pour les valeurs 2, 3, 4, 10 et 100, la médiane est 4 ; la moyenne serait 23,8.',
  },
  MULTIPLE: {
    term: 'x (« fois »)', category: 'Lire les graphiques',
    short: 'Un ratio qui n\'est pas un pourcentage s\'écrit avec un « x » : 15x veut dire « 15 fois ».',
    example: 'PER de 15x : l\'action coûte 15 fois le bénéfice annuel par action.',
  },
  HISTOGRAM: {
    term: 'Répartition des scores', category: 'Lire les graphiques',
    short: 'Combien de sociétés ont un score entre 0 et 9, entre 10 et 19, etc. Chaque barre compte les sociétés d\'une tranche.',
    read: 'Barres concentrées à droite : le profil est généreux avec ce marché. À gauche : peu de sociétés correspondent au profil.',
  },
  SCATTER: {
    term: 'Nuage de points', category: 'Lire les graphiques',
    short: 'Chaque point est une société, placée selon ses notes dans deux profils : à droite si la première est haute, en haut si la seconde l\'est.',
    read: 'En haut à droite (zone teintée) : bonnes notes dans les deux profils, par exemple de belles entreprises à un prix raisonnable. Passez la souris sur un point pour voir la société, cliquez pour ouvrir sa fiche.',
  },
  STRIP: {
    term: 'Bande de comparaison', category: 'Lire les graphiques',
    short: 'Chaque point est une société cotée à Paris, placée selon la valeur d\'un indicateur. Elle montre où se situe la société face aux autres.',
    read: 'Gros point orange : la société. Points bleus : son secteur. Points gris : tout le marché. Trait vertical : la médiane du marché. « mieux » et « moins bien » indiquent le bon sens de lecture.',
    watch: 'Les valeurs les plus extrêmes sont ramenées au bord de la bande, sinon une seule société hors norme écraserait toutes les autres.',
  },
  SCALE_CHART: {
    term: 'Courbe du barème', category: 'Lire les graphiques',
    short: 'Horizontalement, la valeur de l\'indicateur ; verticalement, les points obtenus. Le point noir est la société.',
    read: 'La courbe bleue est le barème général ; l\'orange, s\'il y en a une, le barème adapté au secteur de la société.',
  },
  PRICE_CHART: {
    term: 'Graphique du cours', category: 'Lire les graphiques',
    short: 'L\'évolution du prix de l\'action. Choisissez la période avec les boutons (1 jour à toute l\'histoire disponible).',
    read: 'Sur 1 jour, le trait horizontal est la clôture de la veille : au-dessus, l\'action est en hausse aujourd\'hui. Sur les autres périodes, le pourcentage compare le dernier cours au premier de la période.',
  },
  TODAY_POINT: {
    term: 'Auj. (aujourd\'hui)', category: 'Lire les graphiques',
    short: 'Le dernier point des graphiques de valorisation : le ratio calculé avec le cours du jour et les derniers comptes publiés.',
    what: 'Les autres points sont calculés avec le cours à la date de clôture de chaque exercice.',
  },
}

/** Le lexique d'un identifiant, ou undefined. */
export const glossary = (id: string): Entry | undefined => GLOSSARY[id]
