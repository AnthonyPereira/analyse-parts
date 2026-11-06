package com.analyseparts.ingestion.esef;

import com.analyseparts.ingestion.esef.FilingsXbrlClient.FilingInfo;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Choisit les rapports à importer.
 * <p>
 * Un même exercice est souvent déposé plusieurs fois (version française et anglaise, ou
 * rapport corrigé). Les chiffres sont les mêmes : on n'en garde qu'un par date de clôture :
 * le moins d'erreurs de validation, puis celui du pays de la société, puis le plus récent.
 * <p>
 * Un rapport contient aussi les chiffres de l'exercice précédent : quelques rapports suffisent
 * pour plusieurs années d'historique.
 */
final class FilingSelector {

    private FilingSelector() {
    }

    static List<FilingInfo> select(List<FilingInfo> filings, String companyCountry, int max) {
        Comparator<FilingInfo> preference = Comparator
                .comparing((FilingInfo f) -> f.errorCount() == null ? Integer.MAX_VALUE : f.errorCount())
                .thenComparing(f -> companyCountry != null && companyCountry.equals(f.country()) ? 0 : 1)
                .thenComparing(f -> f.dateAdded() == null ? "" : f.dateAdded(), Comparator.reverseOrder());

        Map<String, FilingInfo> bestByPeriod = filings.stream()
                .filter(f -> f.periodEnd() != null && f.jsonUrl() != null)
                .collect(Collectors.toMap(FilingInfo::periodEnd, f -> f,
                        (a, b) -> preference.compare(a, b) <= 0 ? a : b));

        return bestByPeriod.values().stream()
                .sorted(Comparator.comparing(FilingInfo::periodEnd).reversed())
                .limit(max)
                .toList();
    }
}
