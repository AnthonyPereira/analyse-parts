package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.ReportAnalysis;
import com.analyseparts.financials.FilingRef;
import com.analyseparts.ingestion.ReportDocuments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Une lecture complète : télécharger le rapport, le découper en passages, calculer leurs
 * embeddings, puis poser les questions au modèle.
 */
@Component
public class ReportAnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportAnalysisRunner.class);

    /** En dessous, le texte n'a pas pu être lu correctement (rapport fait d'images, par exemple). */
    static final int MIN_PASSAGES = 30;

    static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final ReportDocuments documents;
    private final PassageSearch search;
    private final ReportAnalyst analyst;
    private final String model;
    private final Clock clock;

    public ReportAnalysisRunner(ReportDocuments documents, PassageSearch search, ReportAnalyst analyst,
                                @Value("${spring.ai.ollama.chat.options.model}") String model) {
        this.documents = documents;
        this.search = search;
        this.analyst = analyst;
        this.model = model;
        this.clock = Clock.systemUTC();
    }

    public ReportAnalysis run(String company, FilingRef filing) throws IOException {
        long start = clock.millis();
        List<String> passages = ReportText.extractPassages(new ByteArrayInputStream(documents.downloadXhtml(filing)));
        if (passages.size() < MIN_PASSAGES) {
            throw new IOException("Texte du rapport presque vide (" + passages.size()
                    + " passages) : rapport sans texte lisible ?");
        }
        long downloaded = clock.millis();
        PassageSearch.Index index;
        ReportAnalyst.Result result;
        long embedded;
        try {
            index = search.buildIndex(passages);
            embedded = clock.millis();
            result = analyst.analyse(company, filing.periodEnd(), index);
        } catch (RuntimeException e) {
            // Ollama échoue parfois de façon passagère après des heures de lecture (sockets épuisés
            // sous Windows : « insufficient buffer space ») : un seul nouvel essai, un peu plus tard
            log.warn("Lecture du rapport de {} interrompue ({}), nouvel essai dans {} s",
                    company, e.getMessage(), RETRY_DELAY.toSeconds());
            pause();
            index = search.buildIndex(passages);
            embedded = clock.millis();
            result = analyst.analyse(company, filing.periodEnd(), index);
        }
        long end = clock.millis();
        log.info("Rapport de {} lu : {} passages, téléchargement {} s, embeddings {} s, questions {} s, {} points écartés",
                company, passages.size(), (downloaded - start) / 1000, (embedded - downloaded) / 1000,
                (end - embedded) / 1000, result.rejected());
        return new ReportAnalysis(filing.sourceId(), filing.periodEnd(), model, clock.instant(), end - start,
                passages.size(), result.rejected(), result.themes());
    }

    private void pause() {
        try {
            Thread.sleep(RETRY_DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompu pendant l'attente", e);
        }
    }
}
