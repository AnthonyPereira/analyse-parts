package com.analyseparts.ingestion.euronext;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Une action cotée, telle que décrite dans la liste Euronext.
 *
 * @param closingPrice cours de clôture de la dernière séance (null si pas de cotation)
 */
public record ListedEquity(String name, String isin, String symbol, String market,
                           String currency, BigDecimal closingPrice, LocalDate closingDate) {

    public ListedEquity(String name, String isin, String symbol, String market) {
        this(name, isin, symbol, market, null, null, null);
    }

    /** Les 2 premières lettres de l'ISIN donnent le pays d'émission du titre. */
    public String isinCountry() {
        return isin.substring(0, 2);
    }

    public boolean hasClosingPrice() {
        return closingPrice != null && closingDate != null && currency != null;
    }
}
