package com.analyseparts.analysis.internal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportTextTest {

    private static List<String> passages(String body) throws Exception {
        String xhtml = """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:ix="http://www.xbrl.org/2013/inlineXBRL">
                <head><title>Rapport</title><style>p { color: red }</style></head>
                <body><div style="display:none"><ix:header><ix:hidden>FAIT CACHE</ix:hidden></ix:header></div>
                %s</body></html>""".formatted(body);
        return ReportText.extractPassages(new ByteArrayInputStream(xhtml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sentence(int i) {
        return "Le groupe conçoit et vend des produits de beauté dans cent cinquante pays, phrase numéro " + i + ". ";
    }

    @Test
    void wordsSplitAcrossSpansAreGluedBackAndHiddenPartsSkipped() throws Exception {
        var result = passages("<p>Développement <span>Dur</span><span>abl</span><span>e</span> : "
                + sentence(1).repeat(2) + "</p>");
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("Développement Durable").doesNotContain("FAIT CACHE", "color");
    }

    @Test
    void blocksAreGroupedIntoPassagesOfBoundedLength() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append("<p>").append(sentence(i)).append("</p>");
        }
        var result = passages(body.toString());
        assertThat(result).hasSizeGreaterThan(3);
        assertThat(result).allSatisfy(p -> assertThat(p.length()).isBetween(80, ReportText.MAX));
        assertThat(String.join(" ", result)).contains("phrase numéro 0.", "phrase numéro 59.");
    }

    @Test
    void shortTableCellsAreSkippedUnlessTablesHoldMostOfTheText() throws Exception {
        String table = "<table><tr><td>Chiffre d'affaires consolidé</td><td>44 050</td><td>43 487</td></tr></table>";
        String text = ("<p>" + sentence(1) + "</p>").repeat(10);
        assertThat(String.join(" ", passages(text + table))).doesNotContain("Chiffre d'affaires consolidé");
        // convertisseur qui met tout le rapport en tableaux : on garde les tableaux
        String all = ("<table><tr><td>" + sentence(2) + "</td></tr></table>").repeat(10) + table;
        assertThat(String.join(" ", passages(all))).contains("Chiffre d'affaires consolidé");
    }

    /** Vu chez L'Oréal : tout le chapitre « Facteurs de risque » est rédigé dans un tableau. */
    @Test
    void paragraphsWrittenInATableAreKeptWithTheirHeading() throws Exception {
        String risk = "<table><tr><td><p>Risques liés à l’activité/Crise sanitaire</p></td></tr>"
                + "<tr><td><p>Identification du risque</p></td><td><p>Gestion du risque</p></td></tr>"
                + "<tr><td><p>Une pandémie pourrait fermer des magasins et des usines, et réduire fortement les ventes "
                + "du Groupe dans les pays touchés pendant plusieurs mois, comme en 2020.</p></td>"
                + "<td><p>Le Groupe a établi des plans de continuité d’activité pour ses usines et ses centrales "
                + "de distribution, testés chaque année.</p></td></tr></table>";
        String text = ("<p>" + sentence(1) + "</p>").repeat(10);
        String joined = String.join(" ", passages(text + risk));
        assertThat(joined).contains("Crise sanitaire", "Une pandémie pourrait fermer", "plans de continuité");
    }

    @Test
    void numericPassagesAreDropped() throws Exception {
        var result = passages("<p>" + "12 345 678 910 1112 1314 ".repeat(10) + "</p>");
        assertThat(result).isEmpty();
    }
}
