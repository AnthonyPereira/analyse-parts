package com.analyseparts.market;

import com.analyseparts.market.internal.QuoteTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Le direct : chaque navigateur ouvert garde une connexion HTTP sur
 * {@code GET /api/quotes/stream}, et reçoit un message à chaque nouveau cours.
 * <p>
 * Technique : <b>Server-Sent Events</b> (SSE). Une réponse HTTP qui ne se termine jamais, dans
 * laquelle le serveur écrit des lignes {@code event: quote / data: {...}} quand il veut. Plus
 * simple qu'un WebSocket (sens unique serveur → navigateur, ce dont on a besoin), et le
 * navigateur se reconnecte tout seul si la connexion tombe ({@code EventSource}).
 * <p>
 * Les cours arrivent ici depuis le topic Kafka {@code quotes} : cette classe est un
 * <b>consommateur</b> du groupe {@value #GROUP}.
 */
@RestController
public class QuoteStream {

    private static final Logger log = LoggerFactory.getLogger(QuoteStream.class);
    /** Pas de limite de durée : la connexion reste ouverte tant que l'onglet est ouvert. */
    private static final long NO_TIMEOUT = 0L;
    /**
     * Le groupe de consommateurs. Kafka retient pour lui la position de lecture (l'offset) de chaque
     * partition : après un redémarrage, l'application reprend là où elle s'était arrêtée.
     * <p>
     * Avec plusieurs instances de l'application, chacune devrait avoir son propre groupe : dans un
     * même groupe, les instances se <em>partagent</em> les partitions, et chaque navigateur ne
     * recevrait qu'une partie des cours.
     */
    static final String GROUP = "quote-stream";

    private final JsonMapper json;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public QuoteStream(JsonMapper json) {
        this.json = json;
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

    /**
     * Reçoit les cours par lots ({@code batch}) : tous les messages arrivés depuis la dernière
     * lecture, envoyés au navigateur en un seul événement.
     * <p>
     * {@code auto.offset.reset=latest} : la toute première fois que le groupe lit le topic, il
     * commence à la fin. Inutile de pousser au navigateur les cours de la veille.
     */
    @KafkaListener(topics = QuoteTopic.NAME, groupId = GROUP, batch = "true",
            properties = "auto.offset.reset=latest")
    void onQuotes(List<String> messages) {
        if (emitters.isEmpty()) {
            return; // personne ne regarde : les messages sont lus (l'offset avance) et ignorés
        }
        List<QuoteMessage> quotes = messages.stream().map(m -> json.readValue(m, QuoteMessage.class)).toList();
        send(SseEmitter.event().name("quotes").data(quotes, MediaType.APPLICATION_JSON));
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
}
