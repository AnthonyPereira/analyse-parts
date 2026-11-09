package com.analyseparts.ingestion;

import java.time.LocalDate;
import java.util.List;

/**
 * Les rapports financiers semestriels déposés à l'AMF (des PDF, sans balisage ESEF), pour qui
 * veut en lire les comptes : le module {@code analysis} (ADR 0015).
 */
public interface HalfYearDocuments {

    /** Un dépôt : sa date de publication, son titre et l'adresse du PDF. */
    record HalfYearDocument(LocalDate publishedOn, String title, String url) {
    }

    /**
     * Les dépôts publiés depuis {@code since}, du plus récent au plus ancien, sans les simples avis
     * de mise à disposition. Vide pour une société dont l'AMF n'est pas l'autorité.
     */
    List<HalfYearDocument> reports(String isin, String lei, LocalDate since);

    /** Télécharge un PDF (quelques Mo). */
    byte[] download(String url);
}
