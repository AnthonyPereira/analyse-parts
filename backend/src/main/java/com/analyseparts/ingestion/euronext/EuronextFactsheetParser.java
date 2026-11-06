package com.analyseparts.ingestion.euronext;

import com.analyseparts.company.Icb;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit les blocs HTML de la fiche d'une action sur live.euronext.com.
 * <p>
 * Les deux blocs sont des tableaux libellé / valeur :
 * <pre>
 * &lt;td&gt;Subsector&lt;/td&gt; &lt;td&gt;&lt;strong&gt;40204020, Clothing and Accessories&lt;/strong&gt;&lt;/td&gt;
 * &lt;td&gt;Admitted shares&lt;/td&gt; &lt;td&gt;&lt;strong&gt;494,606,080&lt;/strong&gt;&lt;/td&gt;
 * </pre>
 */
final class EuronextFactsheetParser {

    private static final Pattern ROW =
            Pattern.compile("<td>\\s*([^<]+?)\\s*</td>\\s*<td>\\s*<strong>\\s*([^<]*?)\\s*</strong>", Pattern.DOTALL);
    private static final Pattern CODE_LABEL = Pattern.compile("(\\d+)\\s*,\\s*(.+)");

    private EuronextFactsheetParser() {
    }

    /** Bloc « ICB sectorial classification ». Vide si la société n'est pas classée. */
    static Optional<Icb> icb(String html) {
        Map<String, String> rows = rows(html);
        Optional<String[]> subsector = codeAndLabel(rows.get("subsector"));
        if (subsector.isEmpty() || subsector.get()[0].length() != 8) {
            return Optional.empty();
        }
        return Optional.of(new Icb(subsector.get()[0], label(rows.get("industry")), label(rows.get("supersector")),
                label(rows.get("sector")), subsector.get()[1]));
    }

    /** Bloc « Trading information » : nombre d'actions admises à la cotation. */
    static Optional<Long> admittedShares(String html) {
        String value = rows(html).get("admitted shares");
        if (value == null) {
            return Optional.empty();
        }
        String digits = value.replaceAll("[,\\s\\u00a0]", "");
        return digits.matches("\\d+") ? Optional.of(Long.parseLong(digits)) : Optional.empty();
    }

    private static Map<String, String> rows(String html) {
        Map<String, String> rows = new HashMap<>();
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            rows.putIfAbsent(m.group(1).trim().toLowerCase(), m.group(2).trim());
        }
        return rows;
    }

    private static Optional<String[]> codeAndLabel(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher m = CODE_LABEL.matcher(value);
        return m.matches() ? Optional.of(new String[]{m.group(1), m.group(2).trim()}) : Optional.empty();
    }

    private static String label(String value) {
        return codeAndLabel(value).map(cl -> cl[1]).orElse(null);
    }
}
