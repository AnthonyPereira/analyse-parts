package com.analyseparts.ingestion.euronext;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EuronextEquityCsvParserTest {

    private static String sample() throws IOException {
        try (var in = EuronextEquityCsvParserTest.class.getResourceAsStream("/euronext/xpar-sample.csv")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void keepsSharesAndSkipsHeadersAndWarrants() throws IOException {
        List<ListedEquity> equities = EuronextEquityCsvParser.parse(sample());

        assertThat(equities).extracting(ListedEquity::symbol)
                .containsExactly("AC", "AF", "AIR", "TTE", "ABO"); // pas ABBS ni ARVBS (bons)
    }

    @Test
    void readsQuotedFieldsContainingCommas() throws IOException {
        ListedEquity airFrance = EuronextEquityCsvParser.parse(sample()).get(1);

        assertThat(airFrance).isEqualTo(new ListedEquity(
                "AIR FRANCE -KLM", "FR001400J770", "AF", "Euronext Paris, Amsterdam",
                "EUR", new java.math.BigDecimal("12.635"), java.time.LocalDate.of(2026, 11, 5)));
    }

    @Test
    void closingPriceIsOptional() throws IOException {
        // "AB SCIENCE BSA" n'a pas de cotation ("-") mais est filtré ; on vérifie un titre coté
        ListedEquity accor = EuronextEquityCsvParser.parse(sample()).getFirst();
        assertThat(accor.hasClosingPrice()).isTrue();
        assertThat(new ListedEquity("X", "FR0000000000", "X", "Euronext Paris").hasClosingPrice()).isFalse();
    }

    @Test
    void isinCountryIsTheIsinPrefix() throws IOException {
        ListedEquity airbus = EuronextEquityCsvParser.parse(sample()).get(2);

        assertThat(airbus.extractIsinCountry()).isEqualTo("NL"); // Airbus SE est de droit néerlandais
    }

    @Test
    void skipsAmsterdamTechnicalLines() {
        String csv = """
                NAI TREAS SHARES;NL0015000CF4;NAITR;"Euronext Amsterdam";EUR
                PHILIPS BUY BACK;NL0000009322;INPHI;"Euronext Amsterdam";EUR
                CABKA DSC2 TS;NL00150002R5;DSC2S;"Euronext Amsterdam";EUR
                PHILIPS KON;NL0000009538;PHIA;"Euronext Amsterdam";EUR
                """;
        assertThat(EuronextEquityCsvParser.parse(csv)).extracting(ListedEquity::symbol).containsExactly("PHIA");
    }

    @Test
    void splitHandlesEscapedQuotes() {
        assertThat(EuronextEquityCsvParser.split("\"A \"\"B\"\" C\";X;\"d;e\""))
                .containsExactly("A \"B\" C", "X", "d;e");
    }
}
