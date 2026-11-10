package com.analyseparts.ingestion;

import com.analyseparts.financials.FilingRef;

import java.io.IOException;

/**
 * Le texte complet d'un rapport annuel (son XHTML ESEF), pour qui veut le lire et pas seulement
 * ses chiffres : la lecture par le modèle de langage local (module {@code analysis}).
 */
public interface ReportDocuments {

    /** Télécharge le rapport XHTML d'un dépôt (plusieurs dizaines de Mo). */
    byte[] downloadXhtml(FilingRef filing) throws IOException;
}
