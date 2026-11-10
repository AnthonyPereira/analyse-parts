package com.analyseparts.ingestion;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Les places de cotation suivies (ADR 0020) : code MIC, nom dans la liste Euronext pour les 7
 * marchés réglementés d'Euronext, et symbole chez Yahoo (suffixe du mnémonique, code de place
 * renvoyé dans les réponses).
 * <p>
 * Les sociétés d'Euronext viennent de ses listes officielles ; celles des autres places, des
 * émetteurs ESEF (filings.xbrl.org), retrouvés chez Yahoo par leur ISIN. Les marchés « Growth » et
 * « Access » d'Euronext (petites valeurs, règles allégées) ne sont pas importés.
 */
public enum StockExchange {
    XPAR("Euronext Paris", ".PA", "PAR"),
    XAMS("Euronext Amsterdam", ".AS", "AMS"),
    XBRU("Euronext Brussels", ".BR", "BRU"),
    XLIS("Euronext Lisbon", ".LS", "LIS"),
    XMSM("Euronext Dublin", ".IR", "ISE"),
    XOSL("Oslo Børs", ".OL", "OSL"),
    MTAA("Euronext Milan", ".MI", "MIL"),
    // hors Euronext : sociétés trouvées par leur rapport ESEF, puis chez Yahoo (ADR 0020)
    XSTO(null, ".ST", "STO"),
    XCSE(null, ".CO", "CPH"),
    XHEL(null, ".HE", "HEL"),
    XMAD(null, ".MC", "MCE"),
    ASEX(null, ".AT", "ATH"),
    XWBO(null, ".VI", "VIE"),
    XLON(null, ".L", "LSE"),
    XLIT(null, ".VS", "LIT"),
    XRIS(null, ".RG", "RIS"),
    XTAL(null, ".TL", "TAL"),
    XBUD(null, ".BD", "BUD");

    private final String euronextListName;
    private final String yahooSuffix;
    private final String yahooExchange;

    StockExchange(String euronextListName, String yahooSuffix, String yahooExchange) {
        this.euronextListName = euronextListName;
        this.yahooSuffix = yahooSuffix;
        this.yahooExchange = yahooExchange;
    }

    /** Le nom de la place dans les listes Euronext ; {@code null} hors Euronext. */
    public String getEuronextListName() {
        return euronextListName;
    }

    public String getYahooSuffix() {
        return yahooSuffix;
    }

    public String getYahooExchange() {
        return yahooExchange;
    }

    public boolean isEuronext() {
        return euronextListName != null;
    }

    /** Les marchés réglementés d'Euronext, dont les listes de sociétés sont importées. */
    public static List<StockExchange> findEuronextMarkets() {
        return Arrays.stream(values()).filter(StockExchange::isEuronext).toList();
    }

    /** La place d'un code MIC, si elle est suivie. */
    public static Optional<StockExchange> findByMic(String mic) {
        return Arrays.stream(values()).filter(m -> m.name().equals(mic)).findFirst();
    }

    /** La place correspondant au code renvoyé par Yahoo (« GER » pour Xetra), si elle est suivie. */
    public static Optional<StockExchange> findByYahooExchange(String yahooExchange) {
        return Arrays.stream(values()).filter(m -> m.yahooExchange.equals(yahooExchange)).findFirst();
    }

    /**
     * Le marché principal d'une action cotée sur plusieurs places d'Euronext : le premier de la
     * colonne « Market » de la liste (« Euronext Brussels, Paris » : Bruxelles).
     */
    public static Optional<StockExchange> findPrimary(String markets) {
        if (markets == null || markets.isBlank()) {
            return Optional.empty();
        }
        String first = markets.split(",")[0].strip();
        return Arrays.stream(values()).filter(m -> m.isEuronext() && m.euronextListName.equalsIgnoreCase(first))
                .findFirst();
    }
}
