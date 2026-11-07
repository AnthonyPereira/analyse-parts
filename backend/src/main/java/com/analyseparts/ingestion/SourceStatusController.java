package com.analyseparts.ingestion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@code GET /api/sources} : l'état de chaque source externe, pour le bandeau du site. */
@RestController
class SourceStatusController {

    private final SourceHealth health;

    SourceStatusController(SourceHealth health) {
        this.health = health;
    }

    @GetMapping("/api/sources")
    List<SourceHealth.Status> sources() {
        return health.all();
    }
}
