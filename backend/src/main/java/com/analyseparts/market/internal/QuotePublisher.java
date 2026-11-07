package com.analyseparts.market.internal;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import com.analyseparts.market.MarketDataService;
import com.analyseparts.market.Quote;
import com.analyseparts.market.QuoteMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le <b>producteur</b> : chaque cours qui change est écrit dans le topic {@code quotes}.
 * <p>
 * Le module ne sait pas qui lira : aujourd'hui le direct vers le navigateur
 * ({@link com.analyseparts.market.QuoteStream}), demain peut-être des alertes de prix. Ajouter un
 * lecteur ne demandera pas de toucher à cette classe.
 * <p>
 * <b>Garantie choisie : « au plus une fois », sans outbox.</b> Si Kafka est arrêté, le cours est
 * perdu pour le direct (il reste enregistré en base), et le suivant arrive une minute plus tard.
 * Pour un rapport annuel ({@code filings}), on ne pourra pas se le permettre.
 */
@Component
class QuotePublisher {

    private static final Logger log = LoggerFactory.getLogger(QuotePublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final CompanyRepository companies;
    private final Map<Long, String> isinById = new ConcurrentHashMap<>();

    QuotePublisher(KafkaTemplate<String, String> kafka, JsonMapper json, CompanyRepository companies) {
        this.kafka = kafka;
        this.json = json;
        this.companies = companies;
    }

    @EventListener
    void onQuotesUpdated(MarketDataService.QuotesUpdated event) {
        for (Quote q : event.quotes()) {
            String isin = isin(q.companyId());
            if (isin == null) {
                continue;
            }
            QuoteMessage message = new QuoteMessage(isin, q.price(), q.currency(), q.asOf(), q.source(),
                    q.previousClose());
            // send() est asynchrone : il rend la main tout de suite, Kafka confirme plus tard.
            kafka.send(QuoteTopic.NAME, isin, json.writeValueAsString(message))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Cours de {} non publié dans Kafka : {}", isin, error.getMessage());
                        } else if (log.isTraceEnabled()) {
                            log.trace("Cours de {} → partition {}, offset {}", isin,
                                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                        }
                    });
        }
    }

    private String isin(long companyId) {
        return isinById.computeIfAbsent(companyId, id -> companies.findById(id).map(Company::getIsin).orElse(null));
    }
}
