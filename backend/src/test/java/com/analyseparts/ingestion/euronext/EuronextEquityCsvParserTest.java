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
                "AIR FRANCE -KLM", "FR001400J770", "AF", "Euronext Paris, Amsterdam"));
    }

    @Test
    void isinCountryIsTheIsinPrefix() throws IOException {
        ListedEquity airbus = EuronextEquityCsvParser.parse(sample()).get(2);

        assertThat(airbus.isinCountry()).isEqualTo("NL"); // Airbus SE est de droit néerlandais
    }

    @Test
    void splitHandlesEscapedQuotes() {
        assertThat(EuronextEquityCsvParser.split("\"A \"\"B\"\" C\";X;\"d;e\""))
                .containsExactly("A \"B\" C", "X", "d;e");
    }
}
