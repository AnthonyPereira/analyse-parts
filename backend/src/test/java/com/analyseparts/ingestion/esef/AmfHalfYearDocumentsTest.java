package com.analyseparts.ingestion.esef;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Titres réels du flux de l'AMF (juillet-août 2026). */
class AmfHalfYearDocumentsTest {

    @Test
    void noticesAreNotReports() {
        assertThat(AmfHalfYearDocuments.NOTICE.matcher("Mise à disposition du Rapport Financier Semestriel 2026").find()).isTrue();
        assertThat(AmfHalfYearDocuments.NOTICE.matcher("Availability of the 2026 Half-year Financial Report").find()).isTrue();
        assertThat(AmfHalfYearDocuments.NOTICE.matcher("Covivio - Modalités de mise à disposition du Rapport Financier").find()).isTrue();
        assertThat(AmfHalfYearDocuments.NOTICE.matcher("Covivio - Conditions of availability of the 2026 Half-year r").find()).isTrue();
        assertThat(AmfHalfYearDocuments.NOTICE.matcher("Rapport financier semestriel 2026").find()).isFalse();
        assertThat(AmfHalfYearDocuments.NOTICE.matcher("LVMH 2026 First Half Financial report").find()).isFalse();
    }
}
