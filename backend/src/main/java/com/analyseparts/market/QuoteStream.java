package com.analyseparts.market;

import com.analyseparts.company.Company;
import com.analyseparts.company.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Le direct : chaque navigateur ouvert garde une connexion HTTP sur
 * {@code GET /api/quotes/stream}, et reçoit un message à chaque nouveau cours.
 * <p>
 * Technique : <b>Server-Sent Events</b> (SSE). Une réponse HTTP qui ne se termine jamais, dans
 * laquelle le serveur écrit des lignes {@code event: quote / data: {...}} quand il veut. Plus
 * simple qu'un WebSocket (sens unique serveur → navigateur, ce dont on a besoin), et le
 * navigateur se reconnecte tout seul si la connexion tombe ({@code EventSource}).
 */
@RestController
public class QuoteStream {

    private static final Logger log = LoggerFactory.getLogger(QuoteStream.class);
    /** Pas de limite de durée : la connexion reste ouverte tant que l'onglet est ouvert. */
    private static final long NO_TIMEOUT = 0L;

    private final CompanyRepository companies;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<Long, String> isinById = new ConcurrentHashMap<>();

    public QuoteStream(CompanyRepository companies) {
        this.companies = companies;
    }

    /** Ce que reçoit le navigateur, avec l'ISIN pour savoir quelle société a bougé. */
    public record QuoteEvent(String isin, BigDecimal price, String currency, Instant asOf, String source,
                             BigDecimal previousClose) {
    }

    @GetMapping(path = "/api/quotes/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(_ -> emitters.remove(emitter));
        try {
            // Premier message tout de suite : le navigateur sait que la connexion est établie.
            emitter.send(SseEmitter.event().comment("connecté"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    @EventListener
    void onQuotesUpdated(MarketDataService.QuotesUpdated event) {
        if (emitters.isEmpty()) {
            return;
        }
        List<QuoteEvent> payload = event.quotes().stream()
                .map(q -> new QuoteEvent(isin(q.companyId()), q.price(), q.currency(), q.asOf(), q.source(),
                        q.previousClose()))
                .filter(e -> e.isin() != null)
                .toList();
        send(SseEmitter.event().name("quotes").data(payload, MediaType.APPLICATION_JSON));
    }

    /**
     * Un commentaire toutes les 25 s : sans trafic, certains proxys coupent la connexion,
     * et c'est ainsi qu'on détecte les onglets fermés.
     */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        send(SseEmitter.event().comment("ping"));
    }

    public int connections() {
        return emitters.size();
    }

    private void send(SseEmitter.SseEventBuilder message) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(message);
            } catch (IOException | IllegalStateException e) {
                // onglet fermé : on oublie cette connexion
                emitters.remove(emitter);
                log.debug("Connexion SSE fermée : {}", e.getMessage());
            }
        }
    }

    private String isin(long companyId) {
        return isinById.computeIfAbsent(companyId, id -> companies.findById(id).map(Company::getIsin).orElse(null));
    }
}
