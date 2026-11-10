package com.analyseparts.ingestion.esef;

import com.analyseparts.financials.ReportedFact;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;

/**
 * Lit un rapport ESEF au format d'origine : un paquet ZIP contenant une page XHTML dont les
 * chiffres sont balisés en XBRL (« Inline XBRL », iXBRL).
 * <pre>
 * &lt;ix:nonFraction name="ifrs-full:Revenue" contextRef="ctx-1" unitRef="eur"
 *                 scale="6" decimals="-6" format="ixt:num-comma-decimal"&gt;80 807&lt;/ix:nonFraction&gt;
 * </pre>
 * se lit « chiffre d'affaires = 80 807 × 10⁶ EUR sur la période du contexte ctx-1 ». Le contexte
 * (période, éventuel axe) et l'unité sont décrits à part, dans l'en-tête {@code ix:header}.
 * <p>
 * Mêmes règles que {@link XbrlJsonParser} : on ne garde que les faits chiffrés <b>sans axe</b>
 * (contexte sans {@code segment} ni {@code scenario}), sans doublon.
 * <p>
 * Le XML est lu en flux (StAX), sans DTD ni entité externe : un rapport fait souvent plus de
 * 10 Mo, et un fichier venu d'Internet ne doit pas pouvoir faire lire un fichier local.
 */
final class InlineXbrlParser {

    private static final String IX = "http://www.xbrl.org/2013/inlineXBRL";
    private static final String XBRLI = "http://www.xbrl.org/2003/instance";

    private InlineXbrlParser() {
    }

    /** Le rapport : le LEI déclaré et les faits totaux. */
    record ParsedReport(String lei, List<ReportedFact> facts) {
    }

    private record Context(LocalDate start, LocalDate end, boolean hasAxis, String lei) {
    }

    /** Un fait lu, à résoudre une fois tous les contextes et unités connus (ils peuvent être après). */
    private record RawFact(String concept, String contextRef, String unitRef, String text, String format,
                           int scale, boolean negative, Integer decimals) {
    }

    /** Ouvre un paquet ESEF (.zip ou .xbri) et lit son rapport XHTML. */
    static ParsedReport parsePackage(InputStream zip) throws IOException {
        return parse(new java.io.ByteArrayInputStream(extractReport(zip)));
    }

    /** Le rapport XHTML d'un paquet ESEF (.zip ou .xbri), tel quel. */
    static byte[] extractReport(InputStream zip) throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("esef-", ".zip");
        try {
            java.nio.file.Files.copy(zip, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            byte[] best = findReport(file, 0);
            if (best == null) {
                throw new IOException("Aucun rapport XHTML dans le paquet ESEF");
            }
            return best;
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /**
     * Le rapport XHTML d'un paquet. Les dépôts varient : dossier {@code reports/} (à la racine ou
     * sous un dossier au nom de la société), XHTML posé directement à la racine, ou paquet
     * {@code .xbri} (lui-même un zip) emballé dans le zip de l'AMF, ouvert sur un niveau.
     * <p>
     * Lecture par {@link java.util.zip.ZipFile} (répertoire central en fin de fichier) plutôt
     * qu'en flux : certains paquets ont des entrées non compressées que {@code ZipInputStream}
     * refuse (« invalid LOC header »).
     */
    private static byte[] findReport(java.nio.file.Path zipPath, int depth) throws IOException {
        byte[] inReports = null;
        byte[] elsewhere = null;
        try (java.util.zip.ZipFile zip = open(zipPath)) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName().toLowerCase();
                if (entry.isDirectory() || name.startsWith("__macosx/") || name.contains("/._") || name.startsWith("._")) {
                    continue; // fichiers parasites ajoutés par macOS
                }
                if (name.endsWith(".xhtml") || name.endsWith(".html")) {
                    boolean reports = name.startsWith("reports/") || name.contains("/reports/");
                    if (reports ? isBigger(entry, inReports) : isBigger(entry, elsewhere)) {
                        byte[] content;
                        try (InputStream in = zip.getInputStream(entry)) {
                            content = in.readAllBytes();
                        }
                        if (reports) inReports = content; else elsewhere = content;
                    }
                } else if (depth == 0 && (name.endsWith(".xbri") || name.endsWith(".zip"))) {
                    java.nio.file.Path inner = java.nio.file.Files.createTempFile("esef-inner-", ".zip");
                    try {
                        try (InputStream in = zip.getInputStream(entry)) {
                            java.nio.file.Files.copy(in, inner, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        byte[] content = findReport(inner, depth + 1);
                        if (content != null && (inReports == null || content.length > inReports.length)) {
                            inReports = content;
                        }
                    } catch (java.util.zip.ZipException e) {
                        // pièce jointe nommée .zip mais illisible : on cherche dans le reste du paquet
                    } finally {
                        java.nio.file.Files.deleteIfExists(inner);
                    }
                }
            }
        }
        // un seul rapport en principe ; sinon le plus complet, de préférence sous reports/
        return inReports != null ? inReports : elsewhere;
    }

    /** Noms de fichiers en UTF-8 normalement ; certains zips (Windows) les codent en CP437. */
    private static java.util.zip.ZipFile open(java.nio.file.Path zipPath) throws IOException {
        try {
            return new java.util.zip.ZipFile(zipPath.toFile(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.util.zip.ZipException e) {
            if (!String.valueOf(e.getMessage()).contains("CEN header")) {
                throw e;
            }
            return new java.util.zip.ZipFile(zipPath.toFile(), java.nio.charset.Charset.forName("Cp437"));
        }
    }

    private static boolean isBigger(ZipEntry entry, byte[] current) {
        return current == null || entry.getSize() < 0 || entry.getSize() > current.length;
    }

    static ParsedReport parse(InputStream xhtml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);

        Map<String, Context> contexts = new HashMap<>();
        Map<String, String> units = new HashMap<>();
        List<RawFact> raw = new ArrayList<>();
        try {
            XMLStreamReader r = factory.createXMLStreamReader(xhtml);
            // faits ix:nonFraction en cours de lecture (ils peuvent être imbriqués)
            Deque<StringBuilder> texts = new ArrayDeque<>();
            Deque<RawFact> open = new ArrayDeque<>();
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String ns = r.getNamespaceURI();
                    String local = r.getLocalName();
                    if (IX.equals(ns) && "nonFraction".equals(local)) {
                        open.push(readRawFact(r));
                        texts.push(new StringBuilder());
                    } else if (XBRLI.equals(ns) && "context".equals(local)) {
                        String id = r.getAttributeValue(null, "id");
                        contexts.put(id, readContext(r));
                    } else if (XBRLI.equals(ns) && "unit".equals(local)) {
                        String id = r.getAttributeValue(null, "id");
                        units.put(id, readUnit(r));
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && !texts.isEmpty()) {
                    for (StringBuilder t : texts) {
                        t.append(r.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && IX.equals(r.getNamespaceURI())
                        && "nonFraction".equals(r.getLocalName()) && !open.isEmpty()) {
                    RawFact f = open.pop();
                    String text = texts.pop().toString();
                    raw.add(new RawFact(f.concept(), f.contextRef(), f.unitRef(), text, f.format(), f.scale(),
                            f.negative(), f.decimals()));
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException("Rapport iXBRL illisible : " + e.getMessage(), e);
        }

        Map<String, ReportedFact> unique = new LinkedHashMap<>();
        String lei = null;
        for (RawFact f : raw) {
            Context ctx = contexts.get(f.contextRef());
            String unit = units.get(f.unitRef());
            if (ctx == null || unit == null || ctx.hasAxis() || ctx.end() == null) {
                continue;
            }
            BigDecimal number = parseNumber(f.text(), f.format());
            if (number == null) {
                continue;
            }
            BigDecimal value = number.movePointRight(f.scale());
            if (f.negative()) {
                value = value.negate();
            }
            ReportedFact rf = new ReportedFact(f.concept(), ctx.start(), ctx.end(), unit, value, f.decimals());
            unique.putIfAbsent(rf.concept() + '|' + rf.periodStart() + '|' + rf.periodEnd() + '|' + rf.unit(), rf);
            if (lei == null) {
                lei = ctx.lei();
            }
        }
        return new ParsedReport(lei, new ArrayList<>(unique.values()));
    }

    private static RawFact readRawFact(XMLStreamReader r) {
        String scale = r.getAttributeValue(null, "scale");
        String decimals = r.getAttributeValue(null, "decimals");
        Integer dec = null;
        if (decimals != null && decimals.matches("-?\\d+")) {
            dec = Integer.parseInt(decimals);
        }
        return new RawFact(r.getAttributeValue(null, "name"), r.getAttributeValue(null, "contextRef"),
                r.getAttributeValue(null, "unitRef"), null, r.getAttributeValue(null, "format"),
                scale == null || !scale.matches("-?\\d+") ? 0 : Integer.parseInt(scale),
                "-".equals(r.getAttributeValue(null, "sign")), dec);
    }

    /** Lit un {@code xbrli:context} jusqu'à sa fin. */
    private static Context readContext(XMLStreamReader r) throws XMLStreamException {
        Map<String, String> values = new HashMap<>();
        boolean axis = readUntilEnd(r, values, java.util.Set.of("startDate", "endDate", "instant", "identifier"),
                java.util.Set.of("segment", "scenario"));
        return new Context(parseDate(values.get("startDate")),
                parseDate(values.getOrDefault("endDate", values.get("instant"))), axis, values.get("identifier"));
    }

    /** Lit un {@code xbrli:unit} : {@code iso4217:EUR} ou {@code iso4217:EUR/xbrli:shares}. */
    private static String readUnit(XMLStreamReader r) throws XMLStreamException {
        List<String> numerator = new ArrayList<>();
        List<String> denominator = new ArrayList<>();
        boolean inDenominator = false;
        StringBuilder measure = null;
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if ("unitDenominator".equals(r.getLocalName())) {
                    inDenominator = true;
                } else if ("measure".equals(r.getLocalName())) {
                    measure = new StringBuilder();
                }
            } else if (event == XMLStreamConstants.CHARACTERS && measure != null) {
                measure.append(r.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if ("measure".equals(r.getLocalName()) && measure != null) {
                    (inDenominator ? denominator : numerator).add(measure.toString().trim());
                    measure = null;
                }
            }
        }
        String num = String.join("*", numerator);
        return denominator.isEmpty() ? num : num + "/" + String.join("*", denominator);
    }

    /**
     * Lit l'élément courant jusqu'à sa fin, en relevant le texte des éléments {@code wanted}.
     *
     * @return {@code true} si l'un des éléments {@code flags} a été rencontré
     */
    private static boolean readUntilEnd(XMLStreamReader r, Map<String, String> values, java.util.Set<String> wanted,
                                        java.util.Set<String> flags) throws XMLStreamException {
        boolean flagged = false;
        String current = null;
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String local = r.getLocalName();
                flagged |= flags.contains(local);
                if (wanted.contains(local)) {
                    current = local;
                    text.setLength(0);
                }
            } else if (event == XMLStreamConstants.CHARACTERS && current != null) {
                text.append(r.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (r.getLocalName().equals(current)) {
                    values.put(current, text.toString().trim());
                    current = null;
                }
            }
        }
        return flagged;
    }

    /** {@code 2025-12-31} (une date-heure éventuelle est tronquée au jour). */
    private static LocalDate parseDate(String s) {
        return s == null || s.length() < 10 ? null : LocalDate.parse(s.substring(0, 10));
    }

    /**
     * Convertit le texte affiché en nombre selon le format iXBRL (« transformation ») :
     * {@code num-comma-decimal} = virgule décimale (« 1 234,5 »), {@code num-dot-decimal} =
     * point décimal (« 1,234.5 »), {@code fixed-zero} / {@code zerodash} = zéro (« - »).
     * Les noms des versions 1 à 4 du registre de transformations sont acceptés.
     */
    static BigDecimal parseNumber(String text, String format) {
        String f = format == null ? "" : format.substring(format.indexOf(':') + 1).toLowerCase();
        if (f.equals("fixed-zero") || f.equals("fixedzero") || f.equals("zerodash")) {
            return BigDecimal.ZERO;
        }
        String t = text == null ? "" : text.trim();
        String digits;
        if (f.contains("comma-decimal") || f.equals("numcommadecimal") || f.equals("numdotcomma")
                || f.equals("numspacecomma")) {
            digits = t.replaceAll("[^0-9,]", "").replace(',', '.');
        } else if (f.isEmpty() || f.contains("dot-decimal") || f.equals("numdotdecimal") || f.equals("numcommadot")
                || f.equals("numspacedot")) {
            digits = t.replaceAll("[^0-9.]", "");
        } else {
            return null; // format non géré (nombre en toutes lettres…) : on ignore le fait
        }
        if (digits.isEmpty() || digits.chars().filter(c -> c == '.').count() > 1) {
            return null;
        }
        return new BigDecimal(digits);
    }
}
