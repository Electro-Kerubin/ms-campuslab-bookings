package org.campuslab.bookings.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;

/**
 * Configuración de seguridad del microservicio.
 *
 * - Valida el JWT de Azure AD (issuer, firma, expiración) via issuer-uri en application.yml.
 * - Convierte el claim "roles" del token en roles de Spring (ROLE_ADMIN, ROLE_TECNICO...).
 * - Sin sesiones: cada request debe traer su Bearer token.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // activa los @PreAuthorize del controller
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // API de reservas: requiere estar autenticado (los roles finos se controlan con @PreAuthorize)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/bookings/**").authenticated()
                        .anyRequest().denyAll())
                // Somos un API REST sin estado (no cookies de sesión)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Le decimos a Spring que la autenticación viene de un JWT en el header Authorization
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())))
                .csrf(csrf -> csrf.disable()); // sin cookies de sesión, CSRF no aplica

        return http.build();
    }

    /**
     * Azure AD entrega los roles en el claim "roles" (ej: ["ADMIN","TECNICO"]).
     * Este converter los transforma en ROLE_ADMIN, ROLE_TECNICO, etc.
     * para que @PreAuthorize("hasRole('ADMIN')") funcione.
     */
    private JwtAuthenticationConverter jwtAuthConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            // Primero tomamos el claim "roles" del token de Azure AD
            Collection<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return List.of();

            // Luego a cada rol le agregamos el prefijo "ROLE_" que Spring espera
            return roles.stream()
                    .map((String rol) -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + rol.toUpperCase()))
                    .toList();
        });
        return converter;
    }
}
