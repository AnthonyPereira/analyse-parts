package com.analyseparts.analysis.internal;

import com.analyseparts.analysis.AnalysisService;
import com.analyseparts.financials.FilingRecorded;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Un nouveau rapport annuel enregistré : on le met dans la file de lecture, s'il est le plus récent
 * de la société. Désactivable ({@code analyse-parts.analysis.auto=false}) pour garder la carte
 * graphique libre.
 */
@Component
@ConditionalOnProperty(name = "analyse-parts.analysis.auto", havingValue = "true", matchIfMissing = true)
class FilingRecordedAnalysisListener {

    private final AnalysisService analyses;

    FilingRecordedAnalysisListener(AnalysisService analyses) {
        this.analyses = analyses;
    }

    @ApplicationModuleListener
    void handleFilingRecorded(FilingRecorded filing) {
        analyses.handleNewFiling(filing.companyId(), filing.sourceId());
    }
}
