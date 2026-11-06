package com.analyseparts.ingestion.euronext;

import com.analyseparts.company.Icb;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class EuronextFactsheetParserTest {

    private static String fixture(String name) throws IOException {
        try (var in = Objects.requireNonNull(EuronextFactsheetParserTest.class.getResourceAsStream("/euronext/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsTheFourIcbLevels() throws IOException {
        Icb icb = EuronextFactsheetParser.icb(fixture("factsheet-icb-lvmh.html")).orElseThrow();

        assertThat(icb).isEqualTo(new Icb("40204020", "Consumer Discretionary", "Consumer Products and Services",
                "Personal Goods", "Clothing and Accessories"));
        assertThat(icb.industryCode()).isEqualTo("40");
        assertThat(icb.supersectorCode()).isEqualTo("4020");
    }

    @Test
    void readsABank() throws IOException {
        Icb icb = EuronextFactsheetParser.icb(fixture("factsheet-icb-bnp.html")).orElseThrow();

        assertThat(icb.supersector()).isEqualTo("Banks");
        assertThat(icb.industry()).isEqualTo("Financials");
    }

    @Test
    void readsAdmittedShares() throws IOException {
        assertThat(EuronextFactsheetParser.admittedShares(fixture("factsheet-trading-lvmh.html"))).contains(494_606_080L);
    }

    @Test
    void emptyBlocks() {
        assertThat(EuronextFactsheetParser.icb("<div></div>")).isEmpty();
        assertThat(EuronextFactsheetParser.admittedShares("<div></div>")).isEmpty();
    }
}
