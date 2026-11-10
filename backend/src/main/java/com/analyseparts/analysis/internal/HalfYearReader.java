package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.HalfYearReport.Item;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Lit le compte de résultat d'un rapport financier semestriel (PDF) : trouve la page, demande au
 * modèle de recopier quelques lignes, puis vérifie que chaque nombre figure bien sur la page.
 * <p>
 * Le texte est extrait dans l'ordre où le PDF l'écrit, pas d'après la position sur la page : un
 * tableau y garde chaque ligne entière (« dont part du Groupe 103 372 »). Recomposer la page d'après
 * les positions décalait les nombres d'une ligne chez Nexans, et le modèle prenait l'impôt pour le
 * résultat net.
 */
@Component
public class HalfYearReader {

    private static final Logger log = LoggerFactory.getLogger(HalfYearReader.class);

    /** Pages envoyées au modèle : le compte de résultat tient sur une page, parfois deux. */
    static final int PAGES = 2;

    /** Longueur gardée de chaque page (environ 2 000 tokens). */
    static final int PAGE_LENGTH = 7000;

    /** En dessous, aucune page ne ressemble à un compte de résultat. */
    static final int MIN_SCORE = 7;

    private final ChatModel chat;
    private final JsonMapper json;
    private final String model;
    private final int contextTokens;

    public HalfYearReader(ChatModel chat, JsonMapper json,
                          @Value("${spring.ai.ollama.chat.options.model}") String model,
                          @Value("${analyse-parts.analysis.context-tokens:10240}") int contextTokens) {
        this.chat = chat;
        this.json = json;
        this.model = model;
        this.contextTokens = contextTokens;
    }

    /** Une ligne lue et retrouvée sur la page, unité appliquée ({@code fullYear} peut manquer). */
    public record Line(Item item, String label, BigDecimal current, BigDecimal previous, BigDecimal fullYear) {
    }

    /** Ce qu'un rapport a donné, ou vide si aucune page ne ressemble à un compte de résultat. */
    public record Reading(LocalDate periodEnd, List<Line> lines, List<String> rejected) {
    }

    /**
     * @param halfYearEnd   la fin du semestre attendu (30 juin 2026)
     * @param fiscalYearEnd la clôture de l'exercice qui le précède (31 décembre 2025)
     */
    public Optional<Reading> read(String company, byte[] pdf, LocalDate halfYearEnd, LocalDate fiscalYearEnd)
            throws IOException {
        List<String> pages = extractPages(pdf);
        List<Integer> chosen = findIncomeStatementPages(pages);
        if (chosen.isEmpty()) {
            log.info("Semestriel {} : aucune page ne ressemble à un compte de résultat ({} pages)", company, pages.size());
            return Optional.empty();
        }
        String text = String.join("\n\n", chosen.stream().map(pages::get).toList());
        Answer answer = LocalModel.ask(chat, json, model, contextTokens, 2500, SYSTEM,
                buildPrompt(company, chosen, pages), SCHEMA, Answer.class);
        log.info("Semestriel {} : pages {} lues, colonnes {}", company, chosen.stream().map(i -> i + 1).toList(),
                answer.colonnes());
        return Optional.of(check(answer, text, halfYearEnd, fiscalYearEnd));
    }

    /** Le texte de chaque page, dans l'ordre d'écriture du PDF. */
    static List<String> extractPages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper(); // sortByPosition = false : l'ordre du PDF
            List<String> pages = new ArrayList<>();
            for (int i = 1; i <= document.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                // espaces insécables (« compte de résultat » chez LVMH) : de simples espaces
                pages.add(stripper.getText(document).replace(' ', ' ').replace(' ', ' '));
            }
            return pages;
        }
    }

    private static final Pattern STATEMENT = Pattern.compile("comptes? de r[ée]sultat|income statement", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern HALF = Pattern.compile("semestre|30 juin|first half|half-year|S1 20", Pattern.CASE_INSENSITIVE);
    private static final Pattern NET = Pattern.compile("r[ée]sultat net|net income|net profit", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TOP_LINE = Pattern.compile(
            "chiffre d.affaires|produit net bancaire|revenus|revenue|ventes|loyers", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d\\s]*[,.]\\d|\\(\\d[\\d\\s]*\\)|\\d{3,}");

    /** Une deuxième page n'est envoyée que si elle est presque aussi probable que la première. */
    static final int SECOND_PAGE_GAP = 2;

    /**
     * Les pages qui ressemblent le plus à un compte de résultat semestriel, dans l'ordre du rapport.
     * Mesuré sur L'Oréal, Nexans, LVMH et Air Liquide : la vraie page arrive en tête (15 points),
     * loin devant la suivante (10 à 13) ; envoyer celle-ci aussi (l'état du résultat global chez
     * LVMH) ne ferait que tenter le modèle avec d'autres « résultat net ».
     */
    static List<Integer> findIncomeStatementPages(List<String> pages) {
        // Le titre du tableau suivi de son unité désigne la page à coup sûr : l'envoyer seule, avec
        // la suivante si le bénéfice par action y a glissé.
        Optional<Integer> titled = IntStream.range(0, pages.size()).boxed()
                .filter(i -> hasStatementTitle(pages.get(i)) && score(pages.get(i)) >= MIN_SCORE)
                .min(Comparator.comparingInt((Integer i) -> -score(pages.get(i))).thenComparingInt(i -> i));
        if (titled.isPresent()) {
            int best = titled.get();
            boolean continued = best + 1 < pages.size() && !PER_SHARE.matcher(pages.get(best)).find()
                    && PER_SHARE.matcher(pages.get(best + 1)).find()
                    && !OTHER_STATEMENT.matcher(extractHead(pages.get(best + 1))).find();
            return continued ? List.of(best, best + 1) : List.of(best);
        }
        List<Integer> ranked = IntStream.range(0, pages.size()).boxed()
                .filter(i -> score(pages.get(i)) >= MIN_SCORE)
                .sorted(Comparator.comparingInt((Integer i) -> -score(pages.get(i))).thenComparingInt(i -> i))
                .limit(PAGES)
                .toList();
        if (ranked.isEmpty()) {
            return ranked;
        }
        int best = score(pages.get(ranked.get(0)));
        return ranked.stream().filter(i -> score(pages.get(i)) >= best - SECOND_PAGE_GAP).sorted().toList();
    }

    /** Le haut de la page, où se trouve son titre. */
    static final int HEAD = 300;

    /**
     * Les autres états financiers, qui ressemblent au compte de résultat (chez LVMH, l'« état global
     * des gains et pertes » passait devant) : reconnus à leur titre.
     */
    private static final Pattern OTHER_STATEMENT = Pattern.compile(
            "r[ée]sultat global|[ée]tat global|comprehensive|variation|tr[ée]sorerie|bilan|balance sheet|annexe|notes to|commentaires"
                    + "|analytique|sectoriel|par secteur|segment|r[ée]conciliation|reconciliation",
            Pattern.CASE_INSENSITIVE);

    /**
     * Le titre du compte de résultat suivi de son unité : « Compte de résultat consolidé (en milliers
     * d'euros) » (Boiron), « Compte de résultat consolidé du 1er semestre 2026 (en millions
     * d'euros) » (ADP). Les tableaux qui l'imitent n'ont pas l'unité juste après : compte de
     * résultat analytique (Icade), information sectorielle (Boiron), commentaires (Bouygues).
     */
    static final Pattern TITLE = Pattern.compile(
            "(comptes? de r[ée]sultats?|(consolidated )?income statements?)[^.()]{0,80}?\\(?\\s*(en|in) (millions|milliers|thousands|m€|k€)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Un titre commence par une majuscule (« Compte de résultat », « COMPTE DE RÉSULTAT »,
     * « Consolidated income statement ») : « Autres éléments du compte de résultat (en millions
     * d'euros) », dans les commentaires de LVMH, n'en est pas un.
     */
    static boolean hasStatementTitle(String page) {
        Matcher m = TITLE.matcher(collapse(page));
        while (m.find()) {
            if (Character.isUpperCase(m.group().charAt(0))) {
                return true;
            }
        }
        return false;
    }

    private static String extractHead(String page) {
        return page.substring(0, Math.min(HEAD, page.length()));
    }

    static int score(String page) {
        long amounts = AMOUNT.matcher(page).results().count();
        String head = extractHead(page);
        return 3 * countMatch(STATEMENT, page) + 2 * countMatch(HALF, page) + 2 * countMatch(NET, page) + 2 * countMatch(TOP_LINE, page)
                + (int) Math.min(3, amounts / 15)
                + 3 * countMatch(STATEMENT, head) - 5 * countMatch(OTHER_STATEMENT, head);
    }

    private static int countMatch(Pattern p, String s) {
        return p.matcher(s).find() ? 1 : 0;
    }

    static final String SYSTEM = """
            Tu lis le rapport financier semestriel d'une société cotée. Tu recopies des chiffres du \
            compte de résultat consolidé, sans rien calculer ni inventer.
            Règles :
            - Uniquement le compte de résultat consolidé (pas un commentaire, pas une division).
            - D'abord les en-têtes des colonnes de montants, de gauche à droite, recopiés tels \
            qu'ils sont écrits sur la page (avec leur date ou leur année), sans la colonne des \
            notes. Ne jamais inventer un en-tête : chacun doit figurer sur la page.
            - Puis, pour chaque ligne, ses montants dans le même ordre que les en-têtes, un par \
            colonne.
            - Tu recopies chaque nombre tel qu'il est écrit (espaces, virgules, parenthèses, signe \
            moins compris), sans le numéro de note qui le précède parfois.
            - Les totaux du groupe : le résultat opérationnel (pas « courant »), le résultat net part \
            du groupe et le résultat de base par action de toutes les activités (pas des seules \
            activités poursuivies, pas « hors éléments non récurrents »).
            - Si une ligne n'existe pas, tu ne la donnes pas.""";

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", ReportAnalyst.buildOrderedMap(
                    "fin_du_semestre", Map.of("type", "string"),
                    "unite", Map.of("type", "string", "enum", List.of("millions", "milliers", "unités")),
                    "colonnes", Map.of("type", "array", "items", Map.of("type", "string")),
                    "lignes", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", ReportAnalyst.buildOrderedMap(
                                            "indicateur", Map.of("type", "string", "enum",
                                                    List.of("CHIFFRE_AFFAIRES", "RESULTAT_OPERATIONNEL",
                                                            "RESULTAT_NET_PART_DU_GROUPE", "BPA_DE_BASE")),
                                            "libelle", Map.of("type", "string"),
                                            "montants", Map.of("type", "array", "items", Map.of("type", "string"))),
                                    "required", List.of("indicateur", "libelle", "montants")))),
            "required", List.of("fin_du_semestre", "unite", "colonnes", "lignes"));

    record Answer(String fin_du_semestre, String unite, List<String> colonnes, List<AnswerLine> lignes) {
    }

    record AnswerLine(String indicateur, String libelle, List<String> montants) {
    }

    /** Quelle colonne est quoi (-1 : absente). */
    record Columns(int current, int previous, int fullYear) {
    }

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(20\\d\\d)(?!\\d)");
    private static final Pattern HALF_WORDS = Pattern.compile(
            "semestre|semester|half|\\bS1\\b|\\bH1\\b|6 mois|six mois", Pattern.CASE_INSENSITIVE);
    private static final String[][] MONTHS = {
            {"janv", "jan"}, {"févr", "fevr", "feb"}, {"mars", "mar"}, {"avr", "apr"}, {"mai", "may"}, {"juin", "jun"},
            {"juil", "jul"}, {"août", "aout", "aug"}, {"sept", "sep"}, {"oct"}, {"nov"}, {"déc", "dec"}};

    /**
     * Les colonnes, d'après leurs en-têtes et non leur place : l'ordre change d'une société à
     * l'autre (L'Oréal : S1 2026, S1 2025, 2025 ; LVMH : 30 juin 2026, 31 déc. 2025, 30 juin 2025 ;
     * Air Liquide : S1 2025, S1 2026), et le modèle s'y trompait quand il choisissait lui-même.
     * Un en-tête de semestre porte l'année et « semestre » ou le mois de fin du semestre ; celui de
     * l'exercice porte l'année de clôture, sans marque de semestre.
     */
    static Columns mapColumns(List<String> headers, LocalDate halfYearEnd, LocalDate fiscalYearEnd) {
        int current = -1, previous = -1, fullYear = -1;
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i) == null ? "" : headers.get(i).toLowerCase();
            List<Integer> years = YEAR.matcher(h).results().map(m -> Integer.parseInt(m.group(1))).toList();
            if (years.size() != 1) {
                continue; // « 2025/2026 », ou pas d'année : ambigu
            }
            int year = years.get(0);
            boolean half = HALF_WORDS.matcher(h).find() || mentions(h, halfYearEnd.getMonthValue());
            boolean yearEnd = mentions(h, fiscalYearEnd.getMonthValue()) || h.contains("exercice") || h.contains("year");
            if (half && year == halfYearEnd.getYear() && current < 0) {
                current = i;
            } else if (half && year == halfYearEnd.getYear() - 1 && previous < 0) {
                previous = i;
            } else if (!half && year == fiscalYearEnd.getYear() && fullYear < 0
                    && (yearEnd || h.strip().equals(String.valueOf(year)))) {
                fullYear = i;
            }
        }
        if (current < 0 || previous < 0) {
            // Des en-têtes qui ne sont que des années (Lectra : « 2026 », « 2025 », « Variation ») :
            // l'année du semestre et la précédente. Le contrôle du chiffre d'affaires (le semestre
            // précédent pèse 30 à 70 % de l'exercice) écartera le cas où « 2025 » serait l'exercice.
            int bareCurrent = -1, barePrevious = -1;
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i) == null ? "" : headers.get(i).strip();
                if (h.equals(String.valueOf(halfYearEnd.getYear())) && bareCurrent < 0) {
                    bareCurrent = i;
                } else if (h.equals(String.valueOf(halfYearEnd.getYear() - 1)) && barePrevious < 0) {
                    barePrevious = i;
                }
            }
            if (current < 0 && previous < 0 && bareCurrent >= 0 && barePrevious >= 0) {
                return new Columns(bareCurrent, barePrevious, -1);
            }
        }
        return new Columns(current, previous, fullYear);
    }

    /** Une date en chiffres : « 30.06.2026 » (Akwel), « 31/03/2026 » (Graines Voltz). */
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<!\\d)\\d{1,2}[./-](\\d{1,2})[./-]20\\d\\d(?!\\d)");

    private static boolean mentions(String header, int month) {
        var numeric = NUMERIC_DATE.matcher(header);
        while (numeric.find()) {
            if (Integer.parseInt(numeric.group(1)) == month) {
                return true;
            }
        }
        for (String name : MONTHS[month - 1]) {
            if (header.contains(name)) {
                return true;
            }
        }
        return false;
    }

    static String buildPrompt(String company, List<Integer> chosen, List<String> pages) {
        StringBuilder sb = new StringBuilder("Société : ").append(company).append(".\n");
        for (int i : chosen) {
            String page = pages.get(i);
            sb.append("\n--- Page ").append(i + 1).append(" ---\n").append(page, 0, Math.min(PAGE_LENGTH, page.length()));
        }
        sb.append("\n\nDonne la date de fin du semestre (AAAA-MM-JJ), l'unité des montants du compte de résultat, ")
                .append("et ces lignes : chiffre d'affaires (ou produit net bancaire, ou revenus, ou ventes), ")
                .append("résultat opérationnel, résultat net part du groupe, résultat net par action de base (en euros).");
        return sb.toString();
    }

    private static final Pattern PER_SHARE = Pattern.compile("par action|per share", Pattern.CASE_INSENSITIVE);
    private static final Pattern DILUTED = Pattern.compile("dilu", Pattern.CASE_INSENSITIVE);

    /**
     * Un résultat partiel, qui ne se compare pas au total des comptes annuels : chez Nexans, le BPA
     * « des activités poursuivies » (2,75 €) à côté d'un BPA annuel de tout le groupe (8,08 €).
     */
    private static final Pattern PARTIAL = Pattern.compile(
            "poursuivies|continuing|abandonn|discontinued|non r[ée]current|hors [ée]l[ée]ments|ajust[ée]|adjusted|courant",
            Pattern.CASE_INSENSITIVE);

    /**
     * Garde les lignes dont chaque nombre figure sur la page, tel qu'écrit, et leur applique l'unité.
     * Une ligne « par action » est un bénéfice par action, quelle que soit la case cochée par le
     * modèle (il rangeait le BPA de L'Oréal sous le résultat net) ; le BPA dilué est ignoré.
     * Sans colonne reconnue pour le semestre et pour le semestre précédent, rien n'est gardé.
     */
    static Reading check(Answer answer, String pageText, LocalDate halfYearEnd, LocalDate fiscalYearEnd) {
        String page = collapse(pageText);
        BigDecimal unit = switch (answer.unite() == null ? "" : answer.unite()) {
            case "millions" -> BigDecimal.valueOf(1_000_000);
            case "milliers" -> BigDecimal.valueOf(1_000);
            default -> BigDecimal.ONE;
        };
        List<Line> lines = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> headers = answer.colonnes() == null ? List.of() : answer.colonnes();
        Columns columns = mapColumns(headers, halfYearEnd, fiscalYearEnd);
        if (columns.current() < 0 || columns.previous() < 0) {
            rejected.add("colonnes non reconnues : " + headers);
            return new Reading(parseDate(answer.fin_du_semestre()), lines, rejected);
        }
        for (AnswerLine l : answer.lignes() == null ? List.<AnswerLine>of() : answer.lignes()) {
            String label = l.libelle() == null ? "" : l.libelle().strip();
            Item item = PER_SHARE.matcher(label).find() ? Item.EPS_BASIC : parseItem(l.indicateur());
            if (item == null || (item == Item.EPS_BASIC && DILUTED.matcher(label).find())
                    || lines.stream().anyMatch(x -> x.item() == item)) {
                continue;
            }
            if (item != Item.REVENUE && PARTIAL.matcher(label).find()) {
                rejected.add(item + " : résultat partiel (« " + label + " »)");
                continue;
            }
            List<String> amounts = l.montants() == null ? List.of() : l.montants();
            if (amounts.size() != headers.size()) {
                rejected.add(item + " : " + amounts.size() + " montants pour " + headers.size() + " colonnes");
                continue;
            }
            String cur = amounts.get(columns.current()), prev = amounts.get(columns.previous());
            String year = columns.fullYear() < 0 ? null : amounts.get(columns.fullYear());
            BigDecimal current = parseNumber(cur), previous = parseNumber(prev);
            BigDecimal fullYear = isBlank(year) ? null : parseNumber(year);
            if (current == null || previous == null || !isOnPage(cur, page) || !isOnPage(prev, page)
                    || (fullYear != null && !isOnPage(year, page))) {
                rejected.add(item + " : nombre absent de la page (" + cur + " / " + prev + ")");
                continue;
            }
            BigDecimal scale = item == Item.EPS_BASIC ? BigDecimal.ONE : unit;
            lines.add(new Line(item, label, current.multiply(scale), previous.multiply(scale),
                    fullYear == null ? null : fullYear.multiply(scale)));
        }
        return new Reading(parseDate(answer.fin_du_semestre()), lines, rejected);
    }

    private static Item parseItem(String indicateur) {
        return switch (indicateur == null ? "" : indicateur) {
            case "CHIFFRE_AFFAIRES" -> Item.REVENUE;
            case "RESULTAT_OPERATIONNEL" -> Item.OPERATING_INCOME;
            case "RESULTAT_NET_PART_DU_GROUPE" -> Item.NET_INCOME_GROUP;
            case "BPA_DE_BASE" -> Item.EPS_BASIC;
            default -> null;
        };
    }

    /** Le nombre tel qu'il est écrit doit être sur la page (espaces normalisés). */
    static boolean isOnPage(String written, String collapsedPage) {
        return !isBlank(written) && collapsedPage.contains(collapse(written));
    }

    private static String collapse(String s) {
        return s.replace(' ', ' ').replace(' ', ' ').replace('−', '-').replaceAll("\\s+", " ").strip();
    }

    /**
     * Un nombre à la française : « 23 776,1 », « (4 272) », « - 5 998,8 », « 8714 », « 11,52 ».
     * Les parenthèses et le signe moins donnent un nombre négatif. Vide si ce n'est pas un nombre.
     */
    static BigDecimal parseNumber(String written) {
        if (isBlank(written)) {
            return null;
        }
        String s = collapse(written);
        boolean negative = s.startsWith("(") || s.startsWith("-");
        String digits = s.replaceAll("[^0-9,.]", "");
        if (digits.isEmpty() || !digits.matches(".*\\d.*")) {
            return null;
        }
        if (digits.contains(",") && digits.contains(".")) {
            digits = digits.replace(".", "").replace(',', '.'); // 1.234,5
        } else if (digits.contains(",")) {
            digits = digits.replace(',', '.');
        } else if (digits.matches("\\d{1,3}(\\.\\d{3})+")) {
            digits = digits.replace(".", ""); // 1.234.567
        }
        try {
            BigDecimal value = new BigDecimal(digits);
            return negative ? value.negate() : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return isBlank(s) ? null : LocalDate.parse(s.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
