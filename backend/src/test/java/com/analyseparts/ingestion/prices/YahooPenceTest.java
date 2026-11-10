package com.analyseparts.ingestion.prices;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Londres cote en pence : les cours sont ramenés en livres (ADR 0020). */
class YahooPenceTest {

    @Test
    void penceAreConvertedToPounds() {
        assertThat(YahooQuoteClient.toMainUnit(new BigDecimal("2715.5"), "GBp")).isEqualByComparingTo("27.155");
        assertThat(YahooQuoteClient.toMainCurrency("GBp")).isEqualTo("GBP");
        assertThat(YahooQuoteClient.toMainCurrency("GBX")).isEqualTo("GBP");
        assertThat(YahooQuoteClient.toMainUnit(new BigDecimal("44.3"), "EUR")).isEqualByComparingTo("44.3");
        assertThat(YahooQuoteClient.toMainCurrency("GBP")).isEqualTo("GBP"); // déjà en livres
        assertThat(YahooQuoteClient.toMainUnit(null, "GBp")).isNull();
    }
}
