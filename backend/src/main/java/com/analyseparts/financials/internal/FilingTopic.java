package com.analyseparts.financials.internal;

import com.analyseparts.financials.FilingRecorded;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import tools.jackson.databind.json.JsonMapper;

/**
 * Le topic {@value FilingRecorded#TOPIC} et la règle d'<b>externalisation</b> : quels événements
 * Spring Modulith recopie dans Kafka, vers quel topic, avec quelle clé, sous quelle forme.
 */
@Configuration(proxyBeanMethods = false)
class FilingTopic {

    /**
     * Rétention <b>illimitée</b> ({@code -1}) : un ou deux rapports par société et par an, quelques
     * milliers de messages en tout. On garde tout, et un futur consommateur (l'analyse par le LLM
     * local, phase 5) pourra relire l'historique complet depuis le début.
     */
    @Bean
    NewTopic filingsTopic() {
        return TopicBuilder.name(FilingRecorded.TOPIC)
                .partitions(3)
                .replicas(1) // un seul broker en local
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .build();
    }

    /**
     * Seul {@link FilingRecorded} part dans Kafka (les autres événements restent internes) :
     * <ul>
     *   <li><b>route</b> : topic {@value FilingRecorded#TOPIC}, clé = ISIN (tous les rapports d'une
     *       société dans la même partition, donc dans l'ordre) ;</li>
     *   <li><b>mapping</b> : écrit en JSON texte, comme le topic {@code quotes} (lisible dans Kafka UI).</li>
     * </ul>
     * Ce bean est créé très tôt (Spring Modulith s'en sert pour filtrer les listeners) : la méthode est
     * {@code static} et le {@code JsonMapper} n'est demandé qu'au premier envoi, sinon on crée une
     * dépendance circulaire avec la configuration de Jackson.
     */
    @Bean
    static EventExternalizationConfiguration eventExternalization(ObjectProvider<JsonMapper> json) {
        return EventExternalizationConfiguration.externalizing()
                .selectByType(FilingRecorded.class)
                .route(FilingRecorded.class, e -> RoutingTarget.forTarget(FilingRecorded.TOPIC).andKey(e.isin()))
                .mapping(FilingRecorded.class, e -> json.getObject().writeValueAsString(e))
                .build();
    }
}
