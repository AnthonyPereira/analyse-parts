package com.analyseparts.ingestion;

import com.analyseparts.ingestion.euronext.EuronextImportServiceAccess;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Les places suivies et le marché principal d'une action cotée sur plusieurs places (ADR 0020). */
class StockExchangeTest {

    @Test
    void thePrimaryMarketIsTheFirstListed() {
        assertThat(StockExchange.findPrimary("Euronext Brussels, Paris")).contains(StockExchange.XBRU);
        assertThat(StockExchange.findPrimary("Euronext Paris, Amsterdam, Brussels")).contains(StockExchange.XPAR);
        assertThat(StockExchange.findPrimary("Oslo Børs")).contains(StockExchange.XOSL);
        assertThat(StockExchange.findPrimary("Euronext Growth Paris")).isEmpty();
        assertThat(StockExchange.findPrimary(null)).isEmpty();
    }

    @Test
    void anUnknownPrimaryMarketFallsBackToTheListRead() {
        assertThat(EuronextImportServiceAccess.findPrimaryMic("Euronext Brussels, Paris", "XPAR")).isEqualTo("XBRU");
        assertThat(EuronextImportServiceAccess.findPrimaryMic("Marché inconnu", "XAMS")).isEqualTo("XAMS");
    }

    @Test
    void yahooSymbolsUseTheSuffixOfEachMarket() {
        assertThat(StockExchange.findByMic("MTAA")).map(StockExchange::getYahooSuffix).contains(".MI");
        assertThat(StockExchange.findByMic("XMSM")).map(StockExchange::getYahooExchange).contains("ISE");
        // Xetra : pas de rapports ESEF gratuits pour l'Allemagne, donc pas suivie
        assertThat(StockExchange.findByMic("XETR")).isEmpty();
        assertThat(StockExchange.findByYahooExchange("LSE")).contains(StockExchange.XLON);
        assertThat(StockExchange.XLON.isEuronext()).isFalse();
    }
}
