package org.campuslab.bookings.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Identifica al usuario de la petición HTTP en curso (JWT de Azure AD) y su IP de origen.
 *
 * Lee del contexto del hilo de la petición, por eso solo sirve mientras se atiende un request:
 * el productor de eventos lo consulta de forma síncrona antes de enviar a Kafka.
 */
@Component
public class ActorProvider {

    // De mayor a menor privilegio: si el token trae varios roles se informa el más alto
    private static final List<String> ROLE_PRIORITY = List.of("ADMIN", "TECNICO", "ESTUDIANTE", "AUDITOR");

    public Actor current() {
        return new Actor(email(), name(), role(), clientIp());
    }

    private Jwt jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }

    // Azure AD pone el correo en "email" o en "preferred_username" según la configuración
    private String email() {
        Jwt jwt = jwt();
        if (jwt == null) return null;
        return jwt.hasClaim("email") ? jwt.getClaimAsString("email") : jwt.getClaimAsString("preferred_username");
    }

    private String name() {
        Jwt jwt = jwt();
        return jwt == null ? null : jwt.getClaimAsString("name");
    }

    private String role() {
        Jwt jwt = jwt();
        if (jwt == null) return null;
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) return null;
        return ROLE_PRIORITY.stream()
                .filter(candidate -> roles.stream().anyMatch(role -> role.equalsIgnoreCase(candidate)))
                .findFirst()
                .orElse(null);
    }

    /**
     * IP del cliente original. Este servicio no es público (solo lo alcanza el BFF), por eso se
     * confía en X-Forwarded-For, que el BFF reenvía desde API Management; si no viene, la IP directa.
     */
    private String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) return null;
        HttpServletRequest request = servletAttributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
