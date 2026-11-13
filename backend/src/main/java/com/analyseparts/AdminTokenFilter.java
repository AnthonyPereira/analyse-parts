package com.analyseparts;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protège les routes d'administration (/api/admin/**) : collectes, recalibrage, relectures.
 * Si {@code analyse-parts.admin-token} est renseigné (en production), chaque appel doit porter
 * l'en-tête {@code X-Admin-Token} avec cette valeur, sinon il reçoit 401. Vide (en local), tout
 * passe comme avant. Pas de Spring Security : un seul administrateur, aucun compte utilisateur.
 *
 * <p>Le chemin comparé est celui que Spring utilise pour choisir la route : décodé ({@code %61}
 * devient {@code a}), sans ce qui suit un {@code ;}, barres obliques doublées réduites. Comparer
 * l'adresse brute laissait passer {@code /api/%61dmin/...} ou {@code /api/admin;x=1/...}
 * (revue du 11 novembre 2026). Par prudence, toute adresse sous /api qui contient encore
 * {@code ;} ou {@code %} est refusée (400) : aucune route de l'API n'en a besoin.
 */
@Component
class AdminTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Token";

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static {
        PATHS.setUrlDecode(true);
        PATHS.setRemoveSemicolonContent(true);
    }

    private final byte[] token;

    AdminTokenFilter(@Value("${analyse-parts.admin-token:}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = normalizePath(request);
        if (path.startsWith("/api/") || path.equals("/api")) {
            String raw = request.getRequestURI();
            if (raw.indexOf(';') >= 0 || raw.indexOf('%') >= 0) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Adresse refusée");
                return;
            }
        }
        if (token.length == 0 || !isAdminPath(path) || hasValidToken(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Jeton d'administration manquant ou faux");
    }

    private static String normalizePath(HttpServletRequest request) {
        return PATHS.getPathWithinApplication(request).replaceAll("/{2,}", "/");
    }

    private static boolean isAdminPath(String path) {
        return path.startsWith("/api/admin/") || path.equals("/api/admin");
    }

    private boolean hasValidToken(HttpServletRequest request) {
        String given = request.getHeader(HEADER);
        // Comparaison à temps constant : la durée ne dit rien du nombre de caractères justes
        return given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }
}
