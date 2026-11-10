package com.analyseparts;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protège les routes d'administration (/api/admin/**) : collectes, recalibrage, relectures.
 * Si {@code analyse-parts.admin-token} est renseigné (en production), chaque appel doit porter
 * l'en-tête {@code X-Admin-Token} avec cette valeur, sinon il reçoit 401. Vide (en local), tout
 * passe comme avant. Pas de Spring Security : un seul administrateur, aucun compte utilisateur.
 */
@Component
class AdminTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Token";

    private final byte[] token;

    AdminTokenFilter(@Value("${analyse-parts.admin-token:}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return token.length == 0 || !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        // Comparaison à temps constant : la durée ne dit rien du nombre de caractères justes
        if (given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Jeton d'administration manquant ou faux");
    }
}
