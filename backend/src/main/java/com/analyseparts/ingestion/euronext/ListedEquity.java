package com.analyseparts.ingestion.euronext;

/** Une action cotée, telle que décrite dans la liste Euronext. */
public record ListedEquity(String name, String isin, String symbol, String market) {

    /** Les 2 premières lettres de l'ISIN donnent le pays d'émission du titre. */
    public String isinCountry() {
        return isin.substring(0, 2);
    }
}
