package org.campuslab.bookings.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Quién y desde dónde: lo que se lee del JWT y de la petición HTTP en curso. */
class ActorProviderTest {

    private final ActorProvider provider = new ActorProvider();

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void leeEmailNombreYRolDelJwt() {
        autenticar(Map.of("email", "rod.baeza@duocuc.cl", "name", "Rodrigo Baeza", "roles", List.of("ADMIN")));

        Actor actor = provider.current();

        assertThat(actor.email()).isEqualTo("rod.baeza@duocuc.cl");
        assertThat(actor.name()).isEqualTo("Rodrigo Baeza");
        assertThat(actor.role()).isEqualTo("ADMIN");
    }

    @Test
    void siNoHayEmailUsaPreferredUsername() {
        autenticar(Map.of("preferred_username", "ana@duocuc.cl", "roles", List.of("ESTUDIANTE")));

        assertThat(provider.current().email()).isEqualTo("ana@duocuc.cl");
    }

    @Test
    void conVariosRolesInformaElDeMayorPrivilegio() {
        autenticar(Map.of("email", "x@duocuc.cl", "roles", List.of("auditor", "tecnico", "estudiante")));

        assertThat(provider.current().role()).isEqualTo("TECNICO");
    }

    @Test
    void unRolDesconocidoNoSeInforma() {
        autenticar(Map.of("email", "x@duocuc.cl", "roles", List.of("INVITADO")));

        assertThat(provider.current().role()).isNull();
    }

    @Test
    void sinAutenticacionTodoEsNulo() {
        Actor actor = provider.current();

        assertThat(actor.email()).isNull();
        assertThat(actor.name()).isNull();
        assertThat(actor.role()).isNull();
        assertThat(actor.ip()).isNull();
    }

    @Test
    void laIpEsLaPrimeraDeXForwardedFor() {
        peticion(request -> request.addHeader("X-Forwarded-For", "200.1.2.3, 10.0.0.1, 127.0.0.1"));

        assertThat(provider.current().ip()).isEqualTo("200.1.2.3");
    }

    @Test
    void sinXForwardedForUsaLaIpDirecta() {
        peticion(request -> request.setRemoteAddr("192.168.1.50"));

        assertThat(provider.current().ip()).isEqualTo("192.168.1.50");
    }

    private void autenticar(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claims(c -> c.putAll(claims))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null));
    }

    private void peticion(java.util.function.Consumer<MockHttpServletRequest> config) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        config.accept(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
