package it.panea.deliveroo.riderpay.common;

import org.springframework.security.core.context.SecurityContextHolder;

/** Estrae l'identita del client OAuth autenticato dal contesto di sicurezza corrente. */
public final class SecurityUtils {

    private SecurityUtils() {}

    /** Il client_id autenticato (claim "sub" del JWT), popolato da Spring Security dopo la verifica del Bearer token. */
    public static String clientIdAutenticato() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
