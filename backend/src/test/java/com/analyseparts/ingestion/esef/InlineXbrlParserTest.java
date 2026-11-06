package com.analyseparts.ingestion.esef;

import com.analyseparts.financials.ReportedFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class InlineXbrlParserTest {

    /** Un rapport minimal : 2 contextes sans axe, 1 avec axe, des montants en millions. */
    private static final String XHTML = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:ix="http://www.xbrl.org/2013/inlineXBRL"
                  xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                  xmlns:ifrs-full="https://xbrl.ifrs.org/taxonomy/2023-03-23/ifrs-full"
                  xmlns:ixt="http://www.xbrl.org/inlineXBRL/transformation/2020-02-12">
            <body>
              <table>
                <tr><td>Ventes</td>
                    <td><ix:nonFraction name="ifrs-full:Revenue" contextRef="y2025" unitRef="eur" scale="6"
                         decimals="-6" format="ixt:num-comma-decimal">80&#160;807</ix:nonFraction></td></tr>
                <tr><td>Autres charges</td>
                    <td>(<ix:nonFraction name="ifrs-full:OtherOperatingIncomeExpense" contextRef="y2025" unitRef="eur"
                         scale="6" decimals="-6" sign="-" format="ixt:num-comma-decimal">1 234,5</ix:nonFraction>)</td></tr>
                <tr><td>Ventes Asie</td>
                    <td><ix:nonFraction name="ifrs-full:Revenue" contextRef="y2025-asia" unitRef="eur" scale="6"
                         format="ixt:num-comma-decimal">25 000</ix:nonFraction></td></tr>
                <tr><td>BPA</td>
                    <td><ix:nonFraction name="ifrs-full:BasicEarningsLossPerShare" contextRef="y2025" unitRef="eps"
                         decimals="2" format="ixt:num-comma-decimal">22,64</ix:nonFraction></td></tr>
                <tr><td>Goodwill</td>
                    <td><ix:nonFraction name="ifrs-full:Goodwill" contextRef="end2025" unitRef="eur" scale="6"
                         format="ixt:fixed-zero">-</ix:nonFraction></td></tr>
              </table>
              <div style="display:none"><ix:header><ix:resources>
                <xbrli:context id="y2025"><xbrli:entity><xbrli:identifier scheme="http://standards.iso.org/iso/17442">LEI123</xbrli:identifier></xbrli:entity>
                  <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period></xbrli:context>
                <xbrli:context id="end2025"><xbrli:entity><xbrli:identifier scheme="http://standards.iso.org/iso/17442">LEI123</xbrli:identifier></xbrli:entity>
                  <xbrli:period><xbrli:instant>2025-12-31</xbrli:instant></xbrli:period></xbrli:context>
                <xbrli:context id="y2025-asia"><xbrli:entity><xbrli:identifier scheme="http://standards.iso.org/iso/17442">LEI123</xbrli:identifier>
                  <xbrli:segment><xbrldi:explicitMember dimension="ifrs-full:SegmentsAxis">x:Asia</xbrldi:explicitMember></xbrli:segment></xbrli:entity>
                  <xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period></xbrli:context>
                <xbrli:unit id="eur"><xbrli:measure>iso4217:EUR</xbrli:measure></xbrli:unit>
                <xbrli:unit id="eps"><xbrli:divide><xbrli:unitNumerator><xbrli:measure>iso4217:EUR</xbrli:measure></xbrli:unitNumerator>
                  <xbrli:unitDenominator><xbrli:measure>xbrli:shares</xbrli:measure></xbrli:unitDenominator></xbrli:divide></xbrli:unit>
              </ix:resources></ix:header></div>
            </body></html>
            """;

    private static InlineXbrlParser.ParsedReport parse() throws IOException {
        return InlineXbrlParser.parse(new ByteArrayInputStream(XHTML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsTotalsWithScaleSignAndFormat() throws IOException {
        var report = parse();

        assertThat(report.lei()).isEqualTo("LEI123");
        assertThat(report.facts()).containsExactlyInAnyOrder(
                new ReportedFact("ifrs-full:Revenue", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                        "iso4217:EUR", new BigDecimal("80807000000"), -6),
                new ReportedFact("ifrs-full:OtherOperatingIncomeExpense", LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 12, 31), "iso4217:EUR", new BigDecimal("-1234500000"), -6),
                new ReportedFact("ifrs-full:BasicEarningsLossPerShare", LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 12, 31), "iso4217:EUR/xbrli:shares", new BigDecimal("22.64"), 2),
                new ReportedFact("ifrs-full:Goodwill", null, LocalDate.of(2025, 12, 31), "iso4217:EUR",
                        BigDecimal.ZERO, null)); // le CA Asie (ventilé par segment) est écarté
    }

    @Test
    void readsTheReportInsideAnEsefPackage() throws IOException {
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(zip)) {
            out.putNextEntry(new ZipEntry("LEI123-2025-12-31-fr/META-INF/reportPackage.json"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry("LEI123-2025-12-31-fr/reports/report.xhtml"));
            out.write(XHTML.getBytes(StandardCharsets.UTF_8));
        }
        var report = InlineXbrlParser.parsePackage(new ByteArrayInputStream(zip.toByteArray()));

        assertThat(report.facts()).hasSize(4);
    }

    private static byte[] zip(String entry, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(content);
        }
        return bytes.toByteArray();
    }

    @Test
    void reportsFolderAtTheRootOfThePackage() throws IOException {
        // Paquet JCDecaux 2025 : reports/ directement à la racine du zip
        byte[] pkg = zip("reports/jcdecaux-2025-12-31-fr.xhtml", XHTML.getBytes(StandardCharsets.UTF_8));
        assertThat(InlineXbrlParser.parsePackage(new ByteArrayInputStream(pkg)).facts()).hasSize(4);
    }

    @Test
    void packageWrappedInTheAmfZip() throws IOException {
        // Dépôt Trigano : le zip de l'AMF ne contient qu'un paquet .xbri (lui-même un zip)
        byte[] xbri = zip("LEI-2024-08-31-0-fr/reports/report.xhtml", XHTML.getBytes(StandardCharsets.UTF_8));
        byte[] amf = zip("969500KLZPNMO8TUM811-2024-08-31-0-fr.xbri", xbri);
        assertThat(InlineXbrlParser.parsePackage(new ByteArrayInputStream(amf)).facts()).hasSize(4);
    }

    @Test
    void reportAtTheRootWithoutReportsFolder() throws IOException {
        // Altamir, Selectirente, TBSO : le XHTML seul (ou à côté d'un PDF) à la racine du zip
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("TBSO - Rapport Financier Annuel 2025.pdf"));
            out.write(new byte[100]);
            out.putNextEntry(new ZipEntry("tbso-2025-12-31-1-fr.xhtml"));
            out.write(XHTML.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(InlineXbrlParser.parsePackage(new ByteArrayInputStream(bytes.toByteArray())).facts()).hasSize(4);
    }

    @Test
    void nestedZipNextToMacOsFiles() throws IOException {
        // JCDecaux 2021, Poxel 2024 : dossier zippé sur Mac, avec des fichiers __MACOSX/._* parasites
        byte[] inner = zip("jcdecaux-2021-12-31-fr/reports/report.xhtml", XHTML.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("__MACOSX/JCD/._jcdecaux-2021-12-31-fr.zip"));
            out.write(new byte[178]);
            out.putNextEntry(new ZipEntry("JCD/jcdecaux-2021-12-31-fr.zip"));
            out.write(inner);
            out.putNextEntry(new ZipEntry("JCD/DEU2021-JCDecaux FR.html"));
            out.write("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>sans balises</body></html>".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(InlineXbrlParser.parsePackage(new ByteArrayInputStream(bytes.toByteArray())).facts()).hasSize(4);
    }

    @Test
    void numberFormats() {
        assertThat(InlineXbrlParser.number("1,234.5", "ixt:num-dot-decimal")).isEqualByComparingTo("1234.5");
        assertThat(InlineXbrlParser.number("1.234,5", "ixt:numdotcomma")).isEqualByComparingTo("1234.5");
        assertThat(InlineXbrlParser.number("-", "ixt:zerodash")).isEqualByComparingTo("0");
        assertThat(InlineXbrlParser.number("douze", "ixt-sec:numwordsen")).isNull();
    }

    /** Vérification sur un vrai rapport : ESEF_PACKAGE=chemin/vers/rapport.xbri */
    @Test
    @EnabledIfEnvironmentVariable(named = "ESEF_PACKAGE", matches = ".+")
    void realPackage() throws IOException {
        try (var in = new FileInputStream(System.getenv("ESEF_PACKAGE"))) {
            var report = InlineXbrlParser.parsePackage(in);
            System.out.println("LEI " + report.lei() + " : " + report.facts().size() + " faits");
            report.facts().stream()
                    .filter(f -> f.concept().matches("ifrs-full:(Revenue|ProfitLoss|Assets|Equity|BasicEarningsLossPerShare)"))
                    .forEach(f -> System.out.println("  " + f));
            assertThat(report.facts()).isNotEmpty();
        }
    }
}
