-- Base plus légère pour couvrir l'Europe dans l'offre gratuite de Neon (ADR 0020).

-- 1. Faits des rapports : seuls ceux qu'un indicateur peut utiliser (Metric.isUsed) sont gardés.
--    Les rapports ESEF contiennent plus de 10 000 concepts différents ; les indicateurs en lisent 38.
--    Liste tirée des concepts candidats de Metric au 10 novembre 2026 ; « *:X » = X dans toute taxonomie.
DELETE FROM reported_fact
 WHERE concept NOT IN (
    'ifrs-full:Assets',
    'ifrs-full:BasicEarningsLossPerShare',
    'ifrs-full:CashAndCashEquivalents',
    'ifrs-full:CashFlowsFromUsedInOperatingActivities',
    'ifrs-full:CurrentAssets',
    'ifrs-full:CurrentAssetsOtherThanAssetsOrDisposalGroupsClassifiedAsHeldForSaleOrAsHeldForDistributionToOwners',
    'ifrs-full:CurrentBorrowingsAndCurrentPortionOfNoncurrentBorrowings',
    'ifrs-full:CurrentLiabilities',
    'ifrs-full:CurrentLiabilitiesOtherThanLiabilitiesIncludedInDisposalGroupsClassifiedAsHeldForSale',
    'ifrs-full:DilutedEarningsLossPerShare',
    'ifrs-full:DividendsPaid',
    'ifrs-full:DividendsPaidClassifiedAsFinancingActivities',
    'ifrs-full:DividendsPaidToEquityHoldersOfParentClassifiedAsFinancingActivities',
    'ifrs-full:DividendsRecognisedAsDistributionsToOwnersPerShare',
    'ifrs-full:Equity',
    'ifrs-full:EquityAttributableToOwnersOfParent',
    'ifrs-full:Goodwill',
    'ifrs-full:GrossProfit',
    'ifrs-full:IncomeTaxExpenseContinuingOperations',
    'ifrs-full:InsuranceRevenue',
    'ifrs-full:Inventories',
    'ifrs-full:LongtermBorrowings',
    'ifrs-full:NoncurrentPortionOfNoncurrentBorrowings',
    'ifrs-full:ProfitLoss',
    'ifrs-full:ProfitLossAttributableToOwnersOfParent',
    'ifrs-full:ProfitLossBeforeTax',
    'ifrs-full:ProfitLossFromOperatingActivities',
    'ifrs-full:PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities',
    'ifrs-full:PurchaseOfPropertyPlantAndEquipmentIntangibleAssetsOtherThanGoodwillInvestmentPropertyAndOtherNoncurrentAssets',
    'ifrs-full:Revenue',
    'ifrs-full:RevenueFromContractsWithCustomers',
    'ifrs-full:RevenueFromSaleOfGoods',
    'ifrs-full:ShorttermBorrowings'
  )
   AND concept NOT LIKE '%:CostOfRisk'
   AND concept NOT LIKE '%:GrossOperatingIncome'
   AND concept NOT LIKE '%:GrossOperatingIncomeFromContinuingActivities'
   AND concept NOT LIKE '%:NetBankingIncome'
   AND concept NOT LIKE '%:RevenuesFromContinuingActivities'
;

-- 2. Historique des cours : au-delà d'un an, la dernière clôture de chaque semaine et de chaque mois.
--    Les graphiques sur 5 ans s'en contentent ; la fin de mois garde le cours exact de la clôture des
--    exercices (31 décembre, 30 juin…), qui sert aux ratios des années passées.
--    MarketDataService.thinOldCloses refait la même chose après chaque collecte.
DELETE FROM price_history p
 USING (SELECT company_id, day,
               row_number() OVER (PARTITION BY company_id, date_trunc('week', day) ORDER BY day DESC) AS in_week,
               row_number() OVER (PARTITION BY company_id, date_trunc('month', day) ORDER BY day DESC) AS in_month
          FROM price_history
         WHERE day < current_date - INTERVAL '1 year') old
 WHERE p.company_id = old.company_id AND p.day = old.day AND old.in_week > 1 AND old.in_month > 1;
