package com.analyseparts.ingestion.esef;

import com.analyseparts.ingestion.HalfYearDocuments;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/** Les rapports semestriels du flux de l'AMF ({@link AmfFilingsClient}). */
@Service
public class AmfHalfYearDocuments implements HalfYearDocuments {

    /**
     * Les avis qui annoncent le rapport sans le contenir : « Mise à disposition du rapport financier
     * semestriel », « Availability of the 2026 Half-year Financial Report », « Modalités de mise à
     * disposition… ». Quelques pages, aucun compte.
     */
    static final Pattern NOTICE = Pattern.compile(
            "mise à disposition|mise a disposition|modalités|availability|conditions of availability|made available",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final AmfFilingsClient amf;

    public AmfHalfYearDocuments(AmfFilingsClient amf) {
        this.amf = amf;
    }

    @Override
    public List<HalfYearDocument> reports(String isin, String lei, LocalDate since) {
        return amf.halfYearReports(isin, lei, since).stream()
                .filter(f -> f.title() == null || !NOTICE.matcher(f.title()).find())
                .map(f -> new HalfYearDocument(f.publishedOn(), f.title(), f.url()))
                .toList();
    }

    @Override
    public byte[] download(String url) {
        return amf.download(url);
    }
}
