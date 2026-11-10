package com.analyseparts.ingestion.esef;

import com.analyseparts.ingestion.esef.FilingsXbrlClient.Issuer;
import com.analyseparts.ingestion.prices.YahooQuoteClient.YahooListing;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Le choix des émetteurs, des codes ISIN et du titre coté (ADR 0020). */
class EsefIssuerImportServiceTest {

    @Test
    void recentIssuersOutsideEuronextCountriesAreExaminedNewestFirst() {
        var volvo = new Issuer("L1", "AB Volvo", "SE", "2025-12-31");
        var old = new Issuer("L2", "Ancienne AB", "SE", "2022-12-31");
        var lvmh = new Issuer("L3", "LVMH", "FR", "2025-12-31");
        var kyiv = new Issuer("L4", "Kyiv PJSC", "UA", "2025-12-31");
        var nokia = new Issuer("L5", "Nokia", "FI", "2026-06-30");

        assertThat(EsefIssuerImportService.selectIssuers(List.of(volvo, old, lvmh, kyiv, nokia), LocalDate.of(2026, 11, 10)))
                .containsExactly(nokia, volvo);
    }

    /** SAP : 10 codes ISIN, dont des ADR (US) et des obligations ; l'action allemande d'abord. */
    @Test
    void isinsOfTheIssuerCountryAreTriedFirstWithoutBondsAndAdrs() {
        assertThat(EsefIssuerImportService.orderIsins(
                List.of("US8030541051", "XS1234567890", "DE000A13SL34", "DE0007164600", "NL0000000001"), "DE"))
                .containsExactly("DE000A13SL34", "DE0007164600", "NL0000000001");
    }

    @Test
    void onlyAStockOnAFollowedExchangeOutsideEuronextIsKept() {
        var frankfurt = new YahooListing("KN8.F", "FRA", "EQUITY", "Healthcare", "Drug Manufacturers", "Krka", "Frankfurt");
        var paris = new YahooListing("MC.PA", "PAR", "EQUITY", "Consumer Cyclical", "Luxury Goods", "LVMH", "Paris");
        var fund = new YahooListing("XACT.ST", "STO", "ETF", null, null, "XACT", "Stockholm");
        var volvo = new YahooListing("VOLV-B.ST", "STO", "EQUITY", "Industrials", "Farm & Heavy Construction Machinery",
                "AB Volvo", "Stockholm");

        assertThat(EsefIssuerImportService.findStock(List.of(frankfurt, paris, fund, volvo))).contains(volvo);
        assertThat(EsefIssuerImportService.findStock(List.of(frankfurt, paris))).isEmpty();
    }
}
