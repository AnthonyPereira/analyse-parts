package com.analyseparts.scoring.web;

import com.analyseparts.scoring.service.ScoringService.RankingEntry;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Une page du classement : les lignes demandées, plus ce qu'il faut pour dessiner le reste de la
 * page sans télécharger les ~2 000 sociétés (nombre total, histogramme des notes, listes des
 * secteurs et des pays pour les filtres).
 *
 * @param items         les lignes de la page, dans l'ordre du classement
 * @param page          numéro de la page, à partir de 0 (ramené à la dernière page si trop grand)
 * @param totalItems    nombre de lignes qui passent les filtres (notées, ou non notées si {@code scored=false})
 * @param scoredCount   sociétés notées qui passent les filtres
 * @param unscoredCount sociétés non notées qui passent les filtres
 * @param histogram     sociétés notées par tranche de 10 points (0–9, …, 90–100), filtres appliqués
 * @param sectors       tous les super-secteurs du classement, sans filtre (pour la liste déroulante)
 * @param countries     tous les pays du classement, sans filtre
 */
public record RankingPage(List<RankingEntry> items, int page, int size, int totalItems, int totalPages,
                          int scoredCount, int unscoredCount, List<Integer> histogram,
                          List<String> sectors, List<String> countries) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    /** Les filtres : chacun vide = pas de filtre. */
    public record Query(String sector, String country, String search, boolean scored, int page, int size) {
    }

    /** Découpe un classement déjà trié (les sociétés sans note à la fin, voir ScoringService). */
    public static RankingPage of(List<RankingEntry> ranking, Query query) {
        String search = normalize(query.search());
        List<RankingEntry> scored = new ArrayList<>();
        List<RankingEntry> unscored = new ArrayList<>();
        int[] histogram = new int[10];
        for (RankingEntry e : ranking) {
            if (!matches(query.sector(), e.sector()) || !matches(query.country(), e.country())
                    || !(search.isEmpty() || normalize(e.name()).contains(search)
                    || normalize(e.ticker()).contains(search) || normalize(e.isin()).contains(search))) {
                continue;
            }
            if (e.score() == null) {
                unscored.add(e);
            } else {
                scored.add(e);
                histogram[Math.clamp(e.score() / 10, 0, 9)]++;
            }
        }
        List<RankingEntry> selected = query.scored() ? scored : unscored;
        int size = Math.clamp(query.size(), 1, MAX_SIZE);
        int totalPages = Math.max(1, (selected.size() + size - 1) / size);
        int page = Math.clamp(query.page(), 0, totalPages - 1);
        int from = page * size;
        List<RankingEntry> items = List.copyOf(selected.subList(from, Math.min(from + size, selected.size())));
        List<Integer> bins = new ArrayList<>(10);
        for (int count : histogram) {
            bins.add(count);
        }
        return new RankingPage(items, page, size, selected.size(), totalPages, scored.size(), unscored.size(),
                Collections.unmodifiableList(bins), distinct(ranking, RankingEntry::sector),
                distinct(ranking, RankingEntry::country));
    }

    private static boolean matches(String wanted, String actual) {
        return wanted == null || wanted.isBlank() || wanted.equals(actual);
    }

    private static List<String> distinct(List<RankingEntry> ranking, java.util.function.Function<RankingEntry, String> field) {
        return ranking.stream().map(field).filter(Objects::nonNull).distinct().sorted().toList();
    }

    /** Recherche sans tenir compte des majuscules ni des accents : « loreal » trouve « L'ORÉAL ». */
    static String normalize(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{Alnum}]", "")
                .toLowerCase(Locale.ROOT);
    }
}
