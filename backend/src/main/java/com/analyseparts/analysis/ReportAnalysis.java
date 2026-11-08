package com.analyseparts.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * La lecture d'un rapport annuel par le modèle local.
 *
 * @param sourceId     rapport lu
 * @param periodEnd    clôture de l'exercice du rapport
 * @param model        modèle de langage utilisé (ex. {@code qwen3:8b})
 * @param createdAt    fin de l'analyse
 * @param durationMs   durée totale (téléchargement, embeddings, questions)
 * @param passageCount nombre de passages de texte dans le rapport
 * @param rejected     points écartés parce que leur citation est introuvable dans le rapport
 * @param themes       les réponses, par thème
 */
public record ReportAnalysis(String sourceId, LocalDate periodEnd, String model, Instant createdAt, long durationMs,
                             int passageCount, int rejected, List<Theme> themes) {

    /** Un thème (activité, avantages, risques) et ses points. */
    public record Theme(String key, String title, List<Point> points) {
    }

    /**
     * Un point de la réponse.
     *
     * @param title       le point en quelques mots
     * @param explanation une ou deux phrases d'explication, écrites par le modèle
     * @param quote       la phrase du rapport qui l'appuie, telle qu'elle y figure
     * @param passage     le passage complet d'où vient la citation, pour la remettre en contexte
     */
    public record Point(String title, String explanation, String quote, String passage) {
    }
}
