package com.analyseparts.analysis;

/**
 * Où en est la lecture du rapport d'une société.
 *
 * @param state    NONE (jamais lu), QUEUED (en attente), RUNNING (en cours), DONE, FAILED
 * @param error    cause de l'échec de la dernière tentative, s'il y en a un
 * @param analysis la dernière lecture réussie, s'il y en a une (gardée même pendant une nouvelle)
 */
public record AnalysisView(State state, String error, ReportAnalysis analysis) {

    public enum State { NONE, QUEUED, RUNNING, DONE, FAILED }
}
