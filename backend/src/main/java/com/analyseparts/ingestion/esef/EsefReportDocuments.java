package com.analyseparts.ingestion.esef;

import com.analyseparts.financials.FilingRef;
import com.analyseparts.ingestion.ReportDocuments;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Deux sources possibles : le paquet ZIP déposé à l'AMF (on en extrait le XHTML), ou le XHTML
 * publié directement par filings.xbrl.org à côté de son xBRL-JSON.
 */
@Service
public class EsefReportDocuments implements ReportDocuments {

    private final AmfFilingsClient amf;
    private final FilingsXbrlClient filingsXbrl;

    public EsefReportDocuments(AmfFilingsClient amf, FilingsXbrlClient filingsXbrl) {
        this.amf = amf;
        this.filingsXbrl = filingsXbrl;
    }

    @Override
    public byte[] downloadXhtml(FilingRef filing) throws IOException {
        String url = filing.url();
        if (url.startsWith("http")) { // paquet de l'AMF (.zip ou .xbri)
            return InlineXbrlParser.extractReport(new ByteArrayInputStream(amf.download(url)));
        }
        if (url.endsWith(".json")) {
            return filingsXbrl.downloadReport(url);
        }
        throw new IOException("Adresse de rapport inattendue : " + url);
    }
}
