package com.analyseparts.market;

import com.analyseparts.TestcontainersConfiguration;
import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.market.internal.QuoteTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le chemin complet avec un vrai Kafka : un cours enregistré est publié dans le topic
 * {@code quotes}, avec l'ISIN pour clé, et lisible par n'importe quel autre groupe.
 */
@SpringBootTest(properties = {"analyse-parts.ingestion.auto-lei=false", "analyse-parts.amf.enabled=false"})
@Import(TestcontainersConfiguration.class)
class QuoteKafkaIntegrationTest {

    @Autowired
    CompanyRepository companies;
    @Autowired
    MarketDataService market;
    @Autowired
    ConsumerFactory<String, String> consumers;
    @Autowired
    JsonMapper json;

    @Test
    void aChangedQuoteIsPublishedWithTheIsinAsKey() {
        long id = companies.save(new Company("FR0000000077", "KAFKA SA", "KFK", "FR", "Euronext Paris")).getId();

        // Un autre groupe que celui du direct : il reçoit lui aussi tous les messages.
        Properties fromStart = new Properties();
        fromStart.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // nouveau groupe : tout lire
        try (Consumer<String, String> consumer = consumers.createConsumer("test-quotes", null, null, fromStart)) {
            consumer.subscribe(List.of(QuoteTopic.NAME));

            market.recordQuotes(List.of(new Quote(id, new BigDecimal("42.10"), "EUR",
                    Instant.parse("2026-11-07T10:00:00Z"), "YAHOO", new BigDecimal("41.00"))));

            // D'autres tests publient aussi des cours : on cherche celui de notre société.
            ConsumerRecord<String, String> record = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (record == null && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if ("FR0000000077".equals(r.key())) {
                        record = r;
                    }
                }
            }
            assertThat(record).as("message reçu dans le topic").isNotNull();
            assertThat(record.key()).isEqualTo("FR0000000077");
            QuoteMessage message = json.readValue(record.value(), QuoteMessage.class);
            assertThat(message.price()).isEqualByComparingTo("42.10");
            assertThat(message.previousClose()).isEqualByComparingTo("41.00");
            assertThat(message.asOf()).isEqualTo(Instant.parse("2026-11-07T10:00:00Z"));
        }
    }
}
