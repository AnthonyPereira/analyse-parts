package com.analyseparts.financials;

import java.util.List;

/**
 * Indicateurs annuels tirés des comptes consolidés (normes IFRS).
 * <p>
 * Chaque indicateur a une liste de <b>concepts candidats</b>, du plus précis au plus général :
 * les sociétés n'utilisent pas toutes le même concept pour la même notion (ex. le chiffre
 * d'affaires est {@code Revenue} chez LVMH mais {@code RevenueFromContractsWithCustomers}
 * chez Air Liquide). Le premier concept trouvé l'emporte.
 */
public enum Metric {

    // --- Compte de résultat : ce que l'entreprise a gagné ou dépensé sur l'exercice ---

    /**
     * Chiffre d'affaires : total des ventes de l'exercice.
     * En dernier recours, les ventes de biens seules (ex. Sanofi publie ses "ventes nettes"
     * sous ce concept, et ses autres revenus à part).
     */
    REVENUE(Kind.FLOW, "ifrs-full:Revenue", "ifrs-full:RevenueFromContractsWithCustomers",
            "ifrs-full:RevenueFromSaleOfGoods"),
    /** Marge brute : ventes moins coût direct des produits vendus. */
    GROSS_PROFIT(Kind.FLOW, "ifrs-full:GrossProfit"),
    /** Résultat opérationnel : ce que rapporte le métier, avant frais financiers et impôts. */
    OPERATING_INCOME(Kind.FLOW, "ifrs-full:ProfitLossFromOperatingActivities"),
    /** Résultat avant impôt. */
    PROFIT_BEFORE_TAX(Kind.FLOW, "ifrs-full:ProfitLossBeforeTax"),
    /** Impôt sur les bénéfices de l'exercice. */
    INCOME_TAX(Kind.FLOW, "ifrs-full:IncomeTaxExpenseContinuingOperations"),
    /** Résultat net total (y compris la part des actionnaires minoritaires des filiales). */
    NET_INCOME(Kind.FLOW, "ifrs-full:ProfitLoss"),
    /** Résultat net part du groupe : ce qui revient aux actionnaires de la société. */
    NET_INCOME_GROUP(Kind.FLOW, "ifrs-full:ProfitLossAttributableToOwnersOfParent"),
    /** Bénéfice par action (BPA) de base : résultat part du groupe ÷ nombre moyen d'actions. */
    EPS_BASIC(Kind.PER_SHARE, "ifrs-full:BasicEarningsLossPerShare"),
    /** BPA dilué : comme le BPA, en comptant les actions qui pourraient être créées (stock-options…). */
    EPS_DILUTED(Kind.PER_SHARE, "ifrs-full:DilutedEarningsLossPerShare"),

    // --- Bilan : ce que l'entreprise possède et doit, à la date de clôture ---

    /** Total de l'actif (= total du passif). */
    TOTAL_ASSETS(Kind.STOCK, "ifrs-full:Assets"),
    /** Actifs courants : convertibles en argent sous un an (stocks, créances clients, trésorerie…). */
    CURRENT_ASSETS(Kind.STOCK, "ifrs-full:CurrentAssets"),
    /** Passifs courants : dettes à payer sous un an. */
    CURRENT_LIABILITIES(Kind.STOCK, "ifrs-full:CurrentLiabilities"),
    /** Capitaux propres totaux : ce qui appartient aux actionnaires (actif − dettes). */
    EQUITY(Kind.STOCK, "ifrs-full:Equity"),
    /** Capitaux propres part du groupe. */
    EQUITY_GROUP(Kind.STOCK, "ifrs-full:EquityAttributableToOwnersOfParent"),
    /** Trésorerie et équivalents. */
    CASH(Kind.STOCK, "ifrs-full:CashAndCashEquivalents"),
    /** Stocks. */
    INVENTORIES(Kind.STOCK, "ifrs-full:Inventories"),
    /** Goodwill : écart d'acquisition, ce qui a été payé en plus de la valeur comptable des sociétés rachetées. */
    GOODWILL(Kind.STOCK, "ifrs-full:Goodwill"),
    /** Dettes financières à plus d'un an. */
    LONG_TERM_DEBT(Kind.STOCK, "ifrs-full:LongtermBorrowings", "ifrs-full:NoncurrentPortionOfNoncurrentBorrowings"),
    /** Dettes financières à moins d'un an. */
    SHORT_TERM_DEBT(Kind.STOCK, "ifrs-full:CurrentBorrowingsAndCurrentPortionOfNoncurrentBorrowings",
            "ifrs-full:ShorttermBorrowings"),

    // --- Tableau des flux de trésorerie : l'argent réellement encaissé ou décaissé ---

    /** Flux de trésorerie d'exploitation : argent généré par l'activité. */
    OPERATING_CASH_FLOW(Kind.FLOW, "ifrs-full:CashFlowsFromUsedInOperatingActivities"),
    /** Investissements (capex) : achats d'immobilisations (publié en montant positif = décaissement). */
    CAPEX(Kind.FLOW,
            "ifrs-full:PurchaseOfPropertyPlantAndEquipmentIntangibleAssetsOtherThanGoodwillInvestmentPropertyAndOtherNoncurrentAssets",
            "ifrs-full:PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities"),
    /** Dividendes versés aux actionnaires de la société. */
    DIVIDENDS_PAID(Kind.FLOW, "ifrs-full:DividendsPaidToEquityHoldersOfParentClassifiedAsFinancingActivities",
            "ifrs-full:DividendsPaidClassifiedAsFinancingActivities");

    /** Nature de la valeur, qui détermine la période et l'unité attendues. */
    public enum Kind {
        /** Montant cumulé sur l'exercice (≈ 1 an), en devise. */
        FLOW,
        /** Montant à la date de clôture, en devise. */
        STOCK,
        /** Montant par action sur l'exercice, en devise par action. */
        PER_SHARE
    }

    private final Kind kind;
    private final List<String> concepts;

    Metric(Kind kind, String... concepts) {
        this.kind = kind;
        this.concepts = List.of(concepts);
    }

    public Kind kind() {
        return kind;
    }

    public List<String> concepts() {
        return concepts;
    }
}
