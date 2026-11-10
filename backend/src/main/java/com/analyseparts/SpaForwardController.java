package com.analyseparts;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * En production, Spring sert aussi le site React (copié dans static/ à la construction de
 * l'image). Les adresses du site (/societe/FR0000120321, /secteurs…) n'existent que côté
 * navigateur : on renvoie index.html et React Router affiche la bonne page. La liste suit les
 * routes de frontend/src/App.tsx ; les fichiers (/assets/…) et /api restent servis normalement.
 */
@Controller
class SpaForwardController {

    @GetMapping({"/", "/societe/{isin}", "/secteurs", "/secteurs/{code}", "/avance", "/lexique"})
    String index() {
        return "forward:/index.html";
    }
}
