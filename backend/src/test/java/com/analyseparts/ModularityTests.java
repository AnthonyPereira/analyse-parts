package com.analyseparts;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Vérifie l'architecture (voir docs/adr/0001-monolithe-modulaire.md).
 * Ce test échoue si un module utilise une classe interne d'un autre module,
 * dépend d'un module non autorisé, ou s'il y a un cycle entre modules.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void respectsModuleBoundaries() {
        modules.verify();
    }

    /** Génère les diagrammes et la description des modules dans target/spring-modulith-docs. */
    @Test
    void writesDocumentation() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
