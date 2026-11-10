package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.HalfYearReport.Item;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HalfYearReaderTest {

    /** Le compte de résultat semestriel de L'Oréal 2026, tel que le PDF l'écrit. */
    static final String OREAL = """
            Comptes de résultat consolidés comparés
            En millions d'euros Notes 1er semestre 2026 1er semestre 2025 2025
            Chiffre d'affaires 3.1 23 776,1 22 473,3 44 052,0
            Coût des ventes - 5 998,8 - 5 692,6 - 11 313,4
            Résultat opérationnel 4 762,5 4 471,2 8 386,5
            Résultat net 3 552,8 3 372,2 6 133,7
             part du groupe 3 547,1 3 368,0 6 127,2
            Résultat net par action part du groupe (en euros) 10.3 6,65 6,31 11,48
            Résultat net dilué par action part du groupe (en euros) 10.3 6,63 6,29 11,44
            """;

    @Test
    void numbersAreReadTheFrenchWay() {
        assertThat(HalfYearReader.number("23 776,1")).isEqualByComparingTo("23776.1");
        assertThat(HalfYearReader.number("(4 272)")).isEqualByComparingTo("-4272");
        assertThat(HalfYearReader.number("- 5 998,8")).isEqualByComparingTo("-5998.8");
        assertThat(HalfYearReader.number("8714")).isEqualByComparingTo("8714");
        assertThat(HalfYearReader.number("1.234.567")).isEqualByComparingTo("1234567");
        assertThat(HalfYearReader.number("4 736")).isEqualByComparingTo("4736");
        assertThat(HalfYearReader.number("n.s.")).isNull();
        assertThat(HalfYearReader.number("")).isNull();
    }

    static final LocalDate H1 = LocalDate.of(2026, 6, 30);
    static final LocalDate FY = LocalDate.of(2025, 12, 31);

    private static HalfYearReader.AnswerLine line(String indicateur, String libelle, String... amounts) {
        return new HalfYearReader.AnswerLine(indicateur, libelle, List.of(amounts));
    }

    @Test
    void linesAreKeptOnlyWhenEveryNumberIsOnThePage() {
        var answer = new HalfYearReader.Answer("2026-06-30", "millions",
                List.of("1er semestre 2026", "1er semestre 2025", "2025"), List.of(
                line("CHIFFRE_AFFAIRES", "Chiffre d'affaires", "23 776,1", "22 473,3", "44 052,0"),
                // le modèle invente un chiffre : la ligne est écartée
                line("RESULTAT_OPERATIONNEL", "Résultat opérationnel", "4 800,0", "4 471,2", "8 386,5"),
                // le BPA rangé sous le résultat net (vu sur L'Oréal) : reconnu par son intitulé, sans unité
                line("RESULTAT_NET_PART_DU_GROUPE", "Résultat net par action part du groupe (en euros)", "6,65", "6,31", "11,48"),
                line("RESULTAT_NET_PART_DU_GROUPE", "part du groupe", "3 547,1", "3 368,0", "6 127,2"),
                // le BPA dilué est ignoré
                line("BPA_DE_BASE", "Résultat net dilué par action part du groupe", "6,63", "6,29", "11,44")));

        var reading = HalfYearReader.check(answer, OREAL, H1, FY);

        assertThat(reading.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(reading.lines()).extracting(HalfYearReader.Line::item)
                .containsExactly(Item.REVENUE, Item.EPS_BASIC, Item.NET_INCOME_GROUP);
        assertThat(reading.lines().get(0).current()).isEqualByComparingTo("23776100000");
        assertThat(reading.lines().get(0).fullYear()).isEqualByComparingTo("44052000000");
        assertThat(reading.lines().get(1).current()).isEqualByComparingTo("6.65");
        assertThat(reading.rejected()).singleElement().asString().contains("OPERATING_INCOME");
    }

    /** Les colonnes d'après leurs en-têtes : trois ordres différents dans les rapports 2026. */
    @Test
    void columnsAreRecognisedByTheirHeadersNotTheirPlace() {
        assertThat(HalfYearReader.columns(List.of("1er semestre 2026", "1er semestre 2025", "2025"), H1, FY))
                .isEqualTo(new HalfYearReader.Columns(0, 1, 2)); // L'Oréal
        assertThat(HalfYearReader.columns(List.of("30 juin 2026", "31 déc. 2025", "30 juin 2025"), H1, FY))
                .isEqualTo(new HalfYearReader.Columns(0, 2, 1)); // LVMH
        assertThat(HalfYearReader.columns(List.of("1er semestre 2025", "1er semestre 2026"), H1, FY))
                .isEqualTo(new HalfYearReader.Columns(1, 0, -1)); // Air Liquide
        assertThat(HalfYearReader.columns(List.of("1er semestre 2026", "1er semestre 2025 (a)"), H1, FY))
                .isEqualTo(new HalfYearReader.Columns(0, 1, -1)); // Nexans
        assertThat(HalfYearReader.columns(List.of("30.06.2026", "30.06.2025", "31.12.2025"), H1, FY))
                .isEqualTo(new HalfYearReader.Columns(0, 1, 2)); // Akwel
        assertThat(HalfYearReader.columns(List.of("31/03/2026", "30/09/2025", "31/03/2025"),
                LocalDate.of(2026, 3, 31), LocalDate.of(2025, 9, 30)))
                .isEqualTo(new HalfYearReader.Columns(0, 2, 1)); // Graines Voltz, exercice clos fin septembre
        // Lectra : des années seules, sans le mot « semestre »
        assertThat(HalfYearReader.columns(List.of("2026", "2025", "Variation 2026/2025"), H1, FY))
                .isEqualTo(new HalfYearReader.Columns(0, 1, -1));
        // un bilan (fin de semestre face à fin d'exercice) n'a pas de semestre précédent
        assertThat(HalfYearReader.columns(List.of("1er semestre 2026", "31 déc. 2025"), H1, FY).previous()).isEqualTo(-1);
        // exercice clos le 30 juin (Pernod Ricard) : le semestre finit le 31 décembre
        assertThat(HalfYearReader.columns(List.of("31 décembre 2025", "31 décembre 2024", "30 juin 2025"),
                LocalDate.of(2025, 12, 31), LocalDate.of(2025, 6, 30)))
                .isEqualTo(new HalfYearReader.Columns(0, 1, 2));
    }

    /** Nexans : le BPA des seules activités poursuivies ne se compare pas au BPA annuel du groupe. */
    @Test
    void aPartialResultIsNotKept() {
        String page = "Résultat de base par action des activités poursuivies (en euros) 2,75 3,25";
        var answer = new HalfYearReader.Answer("2026-06-30", "millions", List.of("1er semestre 2026", "1er semestre 2025"),
                List.of(line("BPA_DE_BASE", "Résultat de base par action des activités poursuivies (en euros)", "2,75", "3,25")));
        var reading = HalfYearReader.check(answer, page, H1, FY);
        assertThat(reading.lines()).isEmpty();
        assertThat(reading.rejected()).singleElement().asString().contains("partiel");
    }

    /** Air Liquide met 2025 avant 2026 : les montants suivent les en-têtes. */
    @Test
    void amountsFollowTheHeaders() {
        String page = "Chiffre d'affaires (3) 13 722,2 13 827,9";
        var answer = new HalfYearReader.Answer("2026-06-30", "millions", List.of("1er semestre 2025", "1er semestre 2026"),
                List.of(line("CHIFFRE_AFFAIRES", "Chiffre d'affaires", "13 722,2", "13 827,9")));
        var revenue = HalfYearReader.check(answer, page, H1, FY).lines().get(0);
        assertThat(revenue.current()).isEqualByComparingTo("13827900000");
        assertThat(revenue.previous()).isEqualByComparingTo("13722200000");

        var unknown = new HalfYearReader.Answer("2026-06-30", "millions", List.of("Montant", "Montant"),
                List.of(line("CHIFFRE_AFFAIRES", "Chiffre d'affaires", "13 722,2", "13 827,9")));
        assertThat(HalfYearReader.check(unknown, page, H1, FY).rejected()).singleElement().asString().contains("colonnes");
    }

    @Test
    void theIncomeStatementPageIsFound() {
        String comment = "Commentaires sur l'activité du semestre. Le chiffre d'affaires du premier semestre progresse.";
        String governance = "Le Conseil d'administration s'est réuni six fois.";
        // vu chez LVMH : l'état du résultat global ressemble au compte de résultat, sauf par son titre
        String global = "État global des gains et pertes consolidés\n" + OREAL.substring(OREAL.indexOf('\n') + 1);
        assertThat(HalfYearReader.incomeStatementPages(List.of(governance, comment, OREAL, global, governance)))
                .containsExactly(2);
    }

    /** Le titre suivi de l'unité : extraits réels des rapports semestriels 2026. */
    @Test
    void theStatementTitleIsFollowedByItsUnit() {
        assertThat(HalfYearReader.titled("- 8 - RAPPORT SEMESTRIEL BOIRON – 30 JUIN 2026 COMPTE DE RÉSULTAT CONSOLIDÉ "
                + "(en milliers d'euros) Paragraphes 2026 (6 mois) 2025 (6 mois)")).isTrue();
        assertThat(HalfYearReader.titled("Annexe 1 – États financiers au 30 juin 2026 Compte de résultat consolidé du "
                + "1er semestre 2026 (en millions d'euros) 1er semestre 2026 1er semestre 2025")).isTrue(); // ADP
        assertThat(HalfYearReader.titled("Compte de résultat consolidé En millions d'euros Notes 2026 (6 mois)")).isTrue();
        assertThat(HalfYearReader.titled("Consolidated income statement (in millions of euros) H1 2026 H1 2025")).isTrue();
        // une phrase des commentaires de LVMH, pas un titre
        assertThat(HalfYearReader.titled("1.3 Autres éléments du compte de résultat (en millions d’euros)")).isFalse();
        // le compte de résultat analytique d'Icade n'a pas l'unité juste après son titre
        assertThat(HalfYearReader.titled("1.1. Compte de résultat analytique au 30 juin 2026 Éléments courants : Loyers "
                + "Bruts 170,2 (0,1) 170,2")).isFalse();
    }

    /** Chez Boiron, l'information sectorielle et une note sur IFRS 18 passaient devant le compte de résultat. */
    @Test
    void aTitledPageWinsOverLookAlikes() {
        String statement = "RAPPORT SEMESTRIEL BOIRON – 30 JUIN 2026 COMPTE DE RÉSULTAT CONSOLIDÉ (en milliers d'euros) "
                + "Paragraphes 2026 (6 mois) 2025 (6 mois) Chiffre d'affaires 22 240 321 245 647 Résultat net 12 000 10 000";
        String segments = "INFORMATION SECTORIELLE Le tableau ci-après présente les données au 30 juin 2026 : DONNEES "
                + "RELATIVES AU COMPTE DE RESULTAT France Europe Chiffre d'affaires 120 000 80 000 Résultat net 1 2 3 4 5 "
                + "6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45";
        assertThat(HalfYearReader.incomeStatementPages(List.of(segments, statement, segments))).containsExactly(1);
    }

    /** Le texte suit l'ordre d'écriture du PDF : une ligne du tableau reste entière. */
    @Test
    void pdfTextKeepsEachTableRowTogether() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(50, 700);
                content.showText("dont part du Groupe 103 372");
                content.endText();
            }
            document.save(out);
            pdf = out.toByteArray();
        }
        assertThat(HalfYearReader.pages(pdf)).singleElement().asString().contains("dont part du Groupe 103 372");
    }

    @Test
    void numbersMustAppearAsWritten() {
        assertThat(HalfYearReader.onPage("23 776,1", "Chiffre d'affaires 3.1 23 776,1 22 473,3")).isTrue();
        assertThat(HalfYearReader.onPage("23776,1", "Chiffre d'affaires 3.1 23 776,1 22 473,3")).isFalse();
        assertThat(HalfYearReader.onPage(" ", "x")).isFalse();
        assertThat(new BigDecimal("1")).isNotNull();
    }
}
