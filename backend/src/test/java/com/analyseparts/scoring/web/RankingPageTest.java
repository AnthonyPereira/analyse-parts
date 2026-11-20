package com.analyseparts.scoring.web;

import com.analyseparts.scoring.core.Freshness;
import com.analyseparts.scoring.service.ScoringService.RankingEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RankingPageTest {

    private static RankingEntry entry(String name, Integer score, String sector, String country) {
        return new RankingEntry("FR" + name, name, name.substring(0, 3), country, sector, score, 1.0, null,
                Freshness.UP_TO_DATE, Map.of());
    }

    /** 120 sociétés notées (de 100 à -19… ramené à 0) puis 5 sans note, comme le trie ScoringService. */
    private static List<RankingEntry> ranking() {
        List<RankingEntry> all = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            all.add(entry("SOC" + (1000 + i), Math.max(0, 100 - i), i % 2 == 0 ? "Banks" : "Energy", i % 3 == 0 ? "FR" : "SE"));
        }
        for (int i = 0; i < 5; i++) {
            all.add(entry("NUL" + i, null, "Energy", "FR"));
        }
        return all;
    }

    private static RankingPage.Query query(String sector, String country, String search, boolean scored, int page, int size) {
        return new RankingPage.Query(sector, country, search, scored, page, size);
    }

    @Test
    void cutsTheScoredCompaniesIntoPagesInRankingOrder() {
        RankingPage first = RankingPage.of(ranking(), query(null, null, null, true, 0, 50));
        assertThat(first.items()).hasSize(50);
        assertThat(first.items().getFirst().score()).isEqualTo(100);
        assertThat(first.totalItems()).isEqualTo(120);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.unscoredCount()).isEqualTo(5);

        RankingPage last = RankingPage.of(ranking(), query(null, null, null, true, 2, 50));
        assertThat(last.items()).hasSize(20);
        assertThat(last.items().getFirst().name()).isEqualTo("SOC1100");
    }

    @Test
    void aPageTooFarShowsTheLastOneAndTheSizeIsCapped() {
        RankingPage page = RankingPage.of(ranking(), query(null, null, null, true, 99, 10_000));
        assertThat(page.size()).isEqualTo(RankingPage.MAX_SIZE);
        assertThat(page.page()).isZero();
        assertThat(page.items()).hasSize(120);
    }

    @Test
    void filtersApplyToCountsAndHistogramButNotToTheFilterLists() {
        RankingPage page = RankingPage.of(ranking(), query("Banks", "FR", null, true, 0, 50));
        // pairs et multiples de 3 : multiples de 6 entre 0 et 119
        assertThat(page.totalItems()).isEqualTo(20);
        assertThat(page.items()).allSatisfy(e -> {
            assertThat(e.sector()).isEqualTo("Banks");
            assertThat(e.country()).isEqualTo("FR");
        });
        assertThat(page.histogram().stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        assertThat(page.unscoredCount()).isZero();
        assertThat(page.sectors()).containsExactly("Banks", "Energy");
        assertThat(page.countries()).containsExactly("FR", "SE");
    }

    @Test
    void histogramPutsAHundredInTheLastBin() {
        RankingPage page = RankingPage.of(ranking(), query(null, null, null, true, 0, 50));
        assertThat(page.histogram()).hasSize(10);
        assertThat(page.histogram().get(9)).isEqualTo(11); // 90 à 100
        assertThat(page.histogram().get(0)).isEqualTo(29); // 0 à 9, dont les 19 ramenés à 0
    }

    @Test
    void unscoredCompaniesAreAPageOfTheirOwn() {
        RankingPage page = RankingPage.of(ranking(), query(null, null, null, false, 0, 50));
        assertThat(page.items()).extracting(RankingEntry::name).containsExactly("NUL0", "NUL1", "NUL2", "NUL3", "NUL4");
        assertThat(page.totalItems()).isEqualTo(5);
        assertThat(page.scoredCount()).isEqualTo(120);
    }

    @Test
    void searchIgnoresCaseAccentsAndPunctuation() {
        List<RankingEntry> all = List.of(entry("L'ORÉAL", 90, "Personal Care", "FR"), entry("LVMH", 80, "Personal Care", "FR"));
        assertThat(RankingPage.of(all, query(null, null, "loreal", true, 0, 50)).items())
                .extracting(RankingEntry::name).containsExactly("L'ORÉAL");
        assertThat(RankingPage.of(all, query(null, null, "lvm", true, 0, 50)).totalItems()).isEqualTo(1);
    }
}
