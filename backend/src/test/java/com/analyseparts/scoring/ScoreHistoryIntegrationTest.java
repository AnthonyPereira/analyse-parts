package com.analyseparts.scoring;

import com.analyseparts.TestcontainersConfiguration;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.financials.FilingData;
import com.analyseparts.financials.FilingRecorded;
import com.analyseparts.financials.FinancialsService;
import com.analyseparts.financials.ReportedFact;
import com.analyseparts.scoring.internal.ScoreHistoryRepository;
import com.analyseparts.scoring.internal.ScoreHistoryRepository.Origin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Le chemin complet : rapport enregistré → événement {@code FilingRecorded} (suivi dans
 * {@code event_publication}) → listener de l'historique → table {@code score_history}.
 */
@SpringBootTest(properties = {"analyse-parts.ingestion.auto-lei=false", "analyse-parts.amf.enabled=false",
        "analyse-parts.score-history.backfill-on-startup=false"})
@Import(TestcontainersConfiguration.class)
class ScoreHistoryIntegrationTest {

    @Autowired
    CompanyRepository companies;
    @Autowired
    FinancialsService financials;
    @Autowired
    ScoreHistoryService history;
    @Autowired
    ScoreHistoryRepository repository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;

    @Test
    void aRecordedFilingIsAddedToTheScoreHistory() {
        Company company = companies.save(new Company("FR0000000088", "OUTBOX SA", "OBX", "FR", "Euronext Paris"));
        LocalDate end = LocalDate.of(2025, 12, 31);

        financials.recordFiling(company.getId(), new FilingData("obx-2025", end, "FR", "/obx.json", 0), facts(end));

        // 1. Le listener a écrit les notes du jour et l'exercice reconstitué.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(repository.count(company.getId(), Origin.FILING)).isPositive();
            assertThat(repository.count(company.getId(), Origin.BACKFILL)).isPositive();
        });

        // 2. Spring Modulith a marqué la publication comme terminée.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                 WHERE event_type = ? AND serialized_event LIKE '%obx-2025%' AND completion_date IS NOT NULL
                   AND listener_id LIKE '%scoring%'
                """, Integer.class, FilingRecorded.class.getName())).isEqualTo(1));

        var points = history.findHistory("FR0000000088").orElseThrow();
        assertThat(points).extracting(ScoreHistoryService.Point::origin).contains(Origin.FILING, Origin.BACKFILL);
    }

    @Test
    void theSameEventTwiceLeavesASingleRow() {
        Company company = companies.save(new Company("FR0000000099", "DOUBLON SA", "DBL", "FR", "Euronext Paris"));
        LocalDate end = LocalDate.of(2025, 12, 31);
        financials.recordFiling(company.getId(), new FilingData("dbl-2025", end, "FR", "/dbl.json", 0), facts(end));
        // le listener l'a déjà traité : on attend, puis on rejoue nous-mêmes l'événement
        await().atMost(Duration.ofSeconds(30))
                .until(() -> repository.count(company.getId(), Origin.FILING) > 0);
        int before = repository.count(company.getId(), Origin.FILING);

        FilingRecorded event = json.readValue(jdbc.queryForObject(
                "SELECT serialized_event FROM event_publication WHERE serialized_event LIKE '%dbl-2025%' LIMIT 1",
                String.class), FilingRecorded.class);
        history.recordFiling(event);
        history.recordFiling(event);

        assertThat(repository.count(company.getId(), Origin.FILING)).isEqualTo(before);
    }

    /** Un exercice assez complet pour obtenir des notes. */
    private static List<ReportedFact> facts(LocalDate end) {
        LocalDate start = end.withDayOfYear(1);
        List<ReportedFact> facts = new ArrayList<>();
        Map.of("ifrs-full:Revenue", "100000000", "ifrs-full:ProfitLossFromOperatingActivities", "15000000",
                "ifrs-full:ProfitLoss", "10000000", "ifrs-full:ProfitLossAttributableToOwnersOfParent", "10000000",
                "ifrs-full:CashFlowsFromUsedInOperatingActivities", "14000000",
                "ifrs-full:PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities", "6000000")
                .forEach((c, v) -> facts.add(new ReportedFact(c, start, end, "iso4217:EUR", new BigDecimal(v), -3)));
        Map.of("ifrs-full:Equity", "80000000", "ifrs-full:EquityAttributableToOwnersOfParent", "80000000",
                "ifrs-full:Assets", "200000000", "ifrs-full:CurrentAssets", "60000000",
                "ifrs-full:CurrentLiabilities", "40000000", "ifrs-full:CashAndCashEquivalents", "10000000",
                "ifrs-full:LongtermBorrowings", "30000000")
                .forEach((c, v) -> facts.add(new ReportedFact(c, null, end, "iso4217:EUR", new BigDecimal(v), -3)));
        return facts;
    }
}
