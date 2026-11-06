package com.analyseparts.ingestion.gleif;

import com.analyseparts.ingestion.CompaniesImported;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Lance la recherche des LEI après chaque import de sociétés.
 * <ul>
 *   <li>{@code @TransactionalEventListener} : appelé seulement <em>après le commit</em> de
 *       l'import. Spring Modulith enregistre l'événement et le marque terminé quand cette
 *       méthode réussit.</li>
 *   <li>{@code @Async} : l'import rend la main tout de suite, la recherche (≈ 1 s par société)
 *       continue en arrière-plan.</li>
 * </ul>
 * On n'utilise pas {@code @ApplicationModuleListener} (le raccourci de Modulith), car il ouvre
 * une transaction pour tout le listener : ici, ça ferait une transaction de plusieurs minutes.
 * {@link LeiEnrichmentService} enregistre plutôt chaque société séparément.
 */
@Component
@ConditionalOnProperty(name = "analyse-parts.ingestion.auto-lei", havingValue = "true", matchIfMissing = true)
class LeiEnrichmentTrigger {

    private final LeiEnrichmentService enrichment;

    LeiEnrichmentTrigger(LeiEnrichmentService enrichment) {
        this.enrichment = enrichment;
    }

    @Async
    @TransactionalEventListener
    void on(CompaniesImported event) {
        if (event.created() > 0) {
            enrichment.enrichMissingLeis();
        }
    }
}
