package com.analyseparts.ingestion.esef;

import com.analyseparts.ingestion.esef.NasdaqNordicClient.Share;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NasdaqNordicClientTest {

    private static final List<Share> HELSINKI = List.of(
            new Share("Elisa Oyj", "ELISA", "FI0009007884", 1_000),
            new Share("Ponsse Oyj 1", "PON1V", "FI0009005078", 10),
            new Share("Stora Enso Oyj A", "STEAV", "FI0009005953", 5),
            new Share("Stora Enso Oyj R", "STERV", "FI0009007611", 500));

    @Test
    void legalFormsAndShareClassesAreIgnored() {
        assertThat(NasdaqNordicClient.findIsin(HELSINKI, "Elisa Oyj")).contains("FI0009007884");
        assertThat(NasdaqNordicClient.findIsin(HELSINKI, "PONSSE OYJ")).contains("FI0009005078");
        assertThat(NasdaqNordicClient.normalize("Atlas Copco Aktiebolag (publ)")).isEqualTo("atlas copco aktiebolag");
        assertThat(NasdaqNordicClient.normalize("Novo Nordisk A/S")).isEqualTo("novo nordisk");
    }

    @Test
    void anUnknownNameFindsNothing() {
        assertThat(NasdaqNordicClient.findIsin(HELSINKI, "Finnvera Oyj")).isEmpty();
    }
}
