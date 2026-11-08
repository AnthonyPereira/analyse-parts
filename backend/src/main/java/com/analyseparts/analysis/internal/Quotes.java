package com.analyseparts.analysis.internal;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;

/**
 * Vérifie qu'une citation donnée par le modèle figure <b>vraiment</b> dans le rapport. C'est le
 * garde-fou principal contre les inventions : un point dont la citation est introuvable est rejeté.
 * <p>
 * La comparaison tolère ce qu'un modèle change sans changer le sens : majuscules, espaces,
 * apostrophes et guillemets typographiques, tirets, accents, ponctuation finale, points de
 * suspension (« début … fin » : chaque morceau doit être présent, dans l'ordre). On renvoie alors
 * le texte <b>exact</b> du rapport, pas celui du modèle.
 */
public final class Quotes {

    /** Une citation courte n'en est pas une (« la société », « les risques »). */
    static final int MIN_LENGTH = 25;

    private Quotes() {
    }

    /** Une citation retrouvée : le passage qui la contient et son texte d'origine. */
    public record Match(int passage, String text) {
    }

    /**
     * Cherche la citation d'abord dans le passage indiqué par le modèle, puis dans les autres
     * (il se trompe parfois de numéro sans inventer le texte).
     *
     * @param preferred indice (à partir de 0) du passage indiqué, ou -1
     */
    public static Optional<Match> find(String quote, List<String> passages, int preferred) {
        if (quote == null) {
            return Optional.empty();
        }
        // le modèle recopie parfois le numéro de l'extrait devant la citation : « [8] La Division… »
        quote = quote.replaceFirst("^\\s*\\[\\d+]\\s*", "");
        String[] fragments = Normalized.of(quote).text.split("\\s*(\\.\\.\\.|…)\\s*");
        List<String> parts = java.util.Arrays.stream(fragments).map(String::strip).filter(f -> !f.isEmpty()).toList();
        if (parts.isEmpty() || parts.stream().mapToInt(String::length).sum() < MIN_LENGTH) {
            return Optional.empty();
        }
        if (preferred >= 0 && preferred < passages.size()) {
            Optional<String> found = locate(parts, passages.get(preferred));
            if (found.isPresent()) {
                return Optional.of(new Match(preferred, found.get()));
            }
        }
        for (int i = 0; i < passages.size(); i++) {
            if (i != preferred) {
                Optional<String> found = locate(parts, passages.get(i));
                if (found.isPresent()) {
                    return Optional.of(new Match(i, found.get()));
                }
            }
        }
        return Optional.empty();
    }

    /** Les morceaux, dans l'ordre ; renvoie le texte d'origine du premier au dernier. */
    private static Optional<String> locate(List<String> parts, String passage) {
        Normalized p = Normalized.of(passage);
        int from = 0;
        int start = -1;
        int end = -1;
        for (String part : parts) {
            int at = p.text.indexOf(part, from);
            if (at < 0) {
                return Optional.empty();
            }
            if (start < 0) {
                start = at;
            }
            end = at + part.length();
            from = end;
        }
        return Optional.of(passage.substring(p.origin[start], p.origin[end - 1] + 1).strip());
    }

    /**
     * Un texte simplifié pour comparer, et pour chaque caractère simplifié sa position dans le texte
     * d'origine.
     */
    static final class Normalized {
        final String text;
        final int[] origin;

        private Normalized(String text, int[] origin) {
            this.text = text;
            this.origin = origin;
        }

        static Normalized of(String s) {
            StringBuilder out = new StringBuilder(s.length());
            int[] origin = new int[s.length() + 1];
            boolean space = true; // pas d'espace au début
            for (int i = 0; i < s.length(); i++) {
                char c = simplify(s.charAt(i));
                if (c == 0) {
                    continue;
                }
                if (c == ' ') {
                    if (space) {
                        continue;
                    }
                    space = true;
                } else {
                    space = false;
                }
                origin[out.length()] = i;
                out.append(c);
            }
            // espace et ponctuation finale ignorés (« … les risques. » ou « … les risques »)
            int n = out.length();
            while (n > 0 && " .,;:!?\"".indexOf(out.charAt(n - 1)) >= 0) {
                n--;
            }
            return new Normalized(out.substring(0, n), origin);
        }

        /** Un caractère simplifié, ou 0 pour l'ignorer. */
        private static char simplify(char c) {
            if (Character.isWhitespace(c) || c == ' ' || c == ' ') return ' ';
            switch (c) {
                case '’', '‘', '`', '´', 'ʼ': return '\'';
                case '«', '»', '“', '”', '„', '"': return 0;
                case '●', '•', '▪', '■', '◆', '►', '': return ' '; // puces de liste ( : puce Symbol/Wingdings)
                case '–', '—', '‑', '−': return '-';
                default:
            }
            String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            return base.isEmpty() ? 0 : Character.toLowerCase(base.charAt(0));
        }
    }
}
