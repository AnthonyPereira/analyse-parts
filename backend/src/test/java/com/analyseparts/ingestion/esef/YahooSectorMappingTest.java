package com.analyseparts.ingestion.esef;

import com.analyseparts.company.Icb;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YahooSectorMappingTest {

    private final YahooSectorMapping mapping = new YahooSectorMapping();

    private String supersectorOf(String sector, String industry) {
        return mapping.findIcb(sector, industry).map(Icb::supersector).orElse(null);
    }

    /** Revue du 11 novembre 2026 : Fresnillo, Endeavour Mining… étaient comparées à la chimie. */
    @Test
    void preciousMetalMinersAreBasicResources() {
        assertThat(supersectorOf("Basic Materials", "Gold")).isEqualTo("Basic Resources");
        assertThat(supersectorOf("Basic Materials", "Silver")).isEqualTo("Basic Resources");
        assertThat(supersectorOf("Basic Materials", "Other Precious Metals & Mining")).isEqualTo("Basic Resources");
    }

    @Test
    void lifeInsurersGetTheInsuranceSupersector() {
        assertThat(mapping.findIcb("Financial Services", "Insurance—Life").orElseThrow().extractSupersectorCode())
                .isEqualTo("3030");
    }

    @Test
    void unknownIndustryFallsBackToTheSector() {
        assertThat(supersectorOf("Basic Materials", "Something New")).isEqualTo("Chemicals");
        assertThat(supersectorOf(null, null)).isNull();
    }
}
