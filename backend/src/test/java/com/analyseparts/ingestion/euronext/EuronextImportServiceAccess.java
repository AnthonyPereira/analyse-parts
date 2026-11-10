package com.analyseparts.ingestion.euronext;

/** Donne aux tests d'un autre package l'accès à une méthode du package. */
public final class EuronextImportServiceAccess {

    private EuronextImportServiceAccess() {
    }

    public static String findPrimaryMic(String markets, String listMic) {
        return EuronextImportService.findPrimaryMic(markets, listMic);
    }
}
