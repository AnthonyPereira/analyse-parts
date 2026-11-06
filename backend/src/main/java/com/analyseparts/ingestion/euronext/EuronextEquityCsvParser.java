package com.analyseparts.ingestion.euronext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lit le CSV "actions" d'Euronext (séparateur ';', champs texte entre guillemets).
 * <p>
 * Format observé :
 * <pre>
 * Name;ISIN;Symbol;Market;Currency;"Open Price";...
 * "European Equities"                    &lt;- lignes d'en-tête sans intérêt
 * "06 Nov 2026"
 * ACCOR;FR0000120404;AC;"Euronext Paris";EUR;...
 * </pre>
 * On ne garde que les actions : les bons de souscription (BSA, warrants) sont écartés.
 * Ils partagent le LEI de leur émetteur et n'ont pas de comptes propres.
 */
public final class EuronextEquityCsvParser {

    /** ISIN = 2 lettres pays + 9 caractères alphanumériques + 1 chiffre de contrôle. */
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    private static final Pattern NOT_A_SHARE =
            Pattern.compile("\\b(BSA\\w*|BSAR|WARR|WARRANTS?|DPS|DS)\\b", Pattern.CASE_INSENSITIVE);

    private EuronextEquityCsvParser() {
    }

    public static List<ListedEquity> parse(String csv) {
        List<ListedEquity> equities = new ArrayList<>();
        for (String line : csv.replace("﻿", "").split("\\R")) {
            List<String> fields = split(line);
            if (fields.size() < 4) {
                continue; // ligne de titre ou de date
            }
            String name = fields.get(0).trim();
            String isin = fields.get(1).trim();
            if (!ISIN.matcher(isin).matches() || NOT_A_SHARE.matcher(name).find()) {
                continue;
            }
            equities.add(new ListedEquity(name, isin, fields.get(2).trim(), fields.get(3).trim()));
        }
        return equities;
    }

    /** Découpe une ligne sur ';' en respectant les guillemets. */
    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); // guillemet échappé ""
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ';' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
