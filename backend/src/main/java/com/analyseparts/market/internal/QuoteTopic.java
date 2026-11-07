package com.analyseparts.market.internal;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * Le topic {@code quotes} : un message par changement de cours, clé = ISIN.
 * <p>
 * Spring le crée au démarrage s'il n'existe pas (bean {@link NewTopic}, lu par {@code KafkaAdmin}).
 * <ul>
 *   <li><b>3 partitions</b> : jusqu'à 3 consommateurs d'un même groupe peuvent lire en parallèle ;
 *       l'ordre est garanti pour une société (même clé → même partition), pas entre sociétés,
 *       ce dont on n'a pas besoin.</li>
 *   <li><b>Rétention 1 jour</b> : un cours n'a plus d'intérêt le lendemain (l'historique est en
 *       base). Le topic {@code filings}, lui, gardera ses messages bien plus longtemps.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class QuoteTopic {

    public static final String NAME = "quotes";

    @Bean
    NewTopic quotesTopic() {
        return TopicBuilder.name(NAME)
                .partitions(3)
                .replicas(1) // un seul broker en local
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(1).toMillis()))
                .build();
    }
}
