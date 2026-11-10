package com.analyseparts.financials;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Seuls les faits utiles aux indicateurs sont gardés en base (ADR 0020). */
class MetricStorageTest {

    @Test
    void onlyConceptsThatAMetricCanUseAreKept() {
        assertThat(Metric.isUsed("ifrs-full:Revenue")).isTrue();
        assertThat(Metric.isUsed("bnp:NetBankingIncome")).isTrue(); // « *:NetBankingIncome »
        assertThat(Metric.isUsed("ifrs-full:DescriptionOfAccountingPolicyForLeasesExplanatory")).isFalse();
        assertThat(Metric.isUsed("NetBankingIncome")).isFalse(); // sans taxonomie
    }

    /** La migration V17 a supprimé les autres faits : elle doit connaître chaque concept candidat. */
    @Test
    void theMigrationKeepsEveryCandidateConcept() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V17__slimmer_storage.sql"));
        for (Metric metric : Metric.values()) {
            for (String concept : metric.getConcepts()) {
                String expected = concept.startsWith("*:")
                        ? "NOT LIKE '%:" + concept.substring(2) + "'"
                        : "'" + concept + "'";
                assertThat(sql).as(metric + " " + concept).contains(expected);
            }
        }
    }
}
