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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Le chemin complet de l'étape 3, avec un vrai Kafka :
 * rapport enregistré → outbox ({@code event_publication}) → topic {@code filings} →
 * groupe {@code score-history} → table {@code score_history}.
 */
@SpringBootTest(properties = {"analyse-parts.ingestion.auto-lei=false", "analyse-parts.amf.enabled=false",
        "analyse-parts.score-history.backfill-on-startup=false"})
@Import(TestcontainersConfiguration.class)
class ScoreHistoryKafkaIntegrationTest {

    @Autowired
    CompanyRepository companies;
    @Autowired
    FinancialsService financials;
    @Autowired
    ScoreHistoryService history;
    @Autowired
    ScoreHistoryRepository repository;
    @Autowired
    ConsumerFactory<String, String> consumers;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;

    @Test
    void aRecordedFilingGoesThroughTheOutboxAndKafkaIntoTheScoreHistory() {
        Company company = companies.save(new Company("FR0000000088", "OUTBOX SA", "OBX", "FR", "Euronext Paris"));
        LocalDate end = LocalDate.of(2025, 12, 31);

        Properties fromStart = new Properties();
        fromStart.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = consumers.createConsumer("test-filings", null, null, fromStart)) {
            consumer.subscribe(List.of(FilingRecorded.TOPIC));

            financials.recordFiling(company.getId(), new FilingData("obx-2025", end, "FR", "/obx.json", 0), facts(end));

            // 1. Le message est dans le topic, avec l'ISIN pour clé et le JSON du contrat pour valeur.
            ConsumerRecord<String, String> record = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (record == null && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if ("FR0000000088".equals(r.key())) {
                        record = r;
                    }
                }
            }
            assertThat(record).as("message reçu dans le topic filings").isNotNull();
            FilingRecorded message = json.readValue(record.value(), FilingRecorded.class);
            assertThat(message.sourceId()).isEqualTo("obx-2025");
            assertThat(message.periodEnd()).isEqualTo(end);
        }

        // 2. L'outbox a marqué la publication comme terminée.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                 WHERE event_type = ? AND serialized_event LIKE '%obx-2025%' AND completion_date IS NOT NULL
                """, Integer.class, FilingRecorded.class.getName())).isEqualTo(1));

        // 3. Le consommateur score-history a écrit les notes du jour et l'exercice reconstitué.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(repository.count(company.getId(), Origin.FILING)).isPositive();
            assertThat(repository.count(company.getId(), Origin.BACKFILL)).isPositive();
        });
        var points = history.history("FR0000000088").orElseThrow();
        assertThat(points).extracting(ScoreHistoryService.Point::origin).contains(Origin.FILING, Origin.BACKFILL);
    }

    @Test
    void theSameMessageTwiceLeavesASingleRow() {
        Company company = companies.save(new Company("FR0000000099", "DOUBLON SA", "DBL", "FR", "Euronext Paris"));
        LocalDate end = LocalDate.of(2025, 12, 31);
        financials.recordFiling(company.getId(), new FilingData("dbl-2025", end, "FR", "/dbl.json", 0), facts(end));
        // le consommateur Kafka l'a peut-être déjà traité : on attend, puis on rejoue nous-mêmes
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
