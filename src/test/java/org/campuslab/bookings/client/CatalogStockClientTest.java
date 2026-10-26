package org.campuslab.bookings.client;

import com.sun.net.httpserver.HttpServer;
import org.campuslab.bookings.config.CatalogClientConfig;
import org.campuslab.bookings.config.CatalogProperties;
import org.campuslab.bookings.exception.CatalogUnavailableException;
import org.campuslab.bookings.exception.InsufficientStockException;
import org.campuslab.bookings.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El cliente que mueve el stock en ms-catalog: qué request arma y cómo traduce cada respuesta.
 */
class CatalogStockClientTest {

    private static final String URL = "http://catalog.test/api/catalog/resources/10/booking-stock/1";
    private static final String OK = "{\"resourceId\":10,\"bookingId\":1,\"stockManaged\":true,\"changed\":true,\"quantityAvailable\":2}";

    @AfterEach
    void limpiarSesion() {
        SecurityContextHolder.clearContext();
    }

    private static void iniciarSesionConToken(String token) {
        Jwt jwt = Jwt.withTokenValue(token).header("alg", "none").claim("sub", "tecnico").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    // ------------------------------------------------------------------------------------------
    // Forma de la request y traducción de respuestas (servidor simulado)
    // ------------------------------------------------------------------------------------------
    @Nested
    class ConServidorSimulado {

        private MockRestServiceServer server;
        private CatalogStockClient client;

        @BeforeEach
        void setUp() {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
            server = MockRestServiceServer.bindTo(builder).build();
            client = new CatalogStockClient(builder.build());
        }

        @Test
        void hold_hacePostAlEndpointDeLaReservaConElJwtDelUsuario() {
            iniciarSesionConToken("token-del-tecnico");
            server.expect(requestTo(URL))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer token-del-tecnico"))
                    .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

            client.hold(10L, 1L);

            server.verify();
        }

        @Test
        void release_haceDeleteAlEndpointDeLaReservaConElJwtDelUsuario() {
            iniciarSesionConToken("token-del-tecnico");
            server.expect(requestTo(URL))
                    .andExpect(method(org.springframework.http.HttpMethod.DELETE))
                    .andExpect(header("Authorization", "Bearer token-del-tecnico"))
                    .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

            client.release(10L, 1L);

            server.verify();
        }

        @Test
        void sinSesionNoInventaUnToken() {
            server.expect(requestTo(URL))
                    .andExpect(headerDoesNotExist("Authorization"))
                    .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

            client.hold(10L, 1L);

            server.verify();
        }

        @Test
        void hold_conflictoSeTraduceAStockInsuficienteConElMensajeDelCatalogo() {
            iniciarSesionConToken("t");
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"No hay stock disponible de 'Microscopio' (0 de 3)\"}"));

            assertThatThrownBy(() -> client.hold(10L, 1L))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessage("No hay stock disponible de 'Microscopio' (0 de 3)");
        }

        @Test
        void hold_conflictoConCuerpoIlegibleUsaUnMensajePorDefecto() {
            iniciarSesionConToken("t");
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.TEXT_PLAIN).body("<html>no es json</html>"));

            assertThatThrownBy(() -> client.hold(10L, 1L))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("No hay stock disponible");
        }

        @Test
        void hold_recursoInexistenteEnElCatalogoSeTraduceANotFound() {
            iniciarSesionConToken("t");
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

            assertThatThrownBy(() -> client.hold(10L, 1L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("10");
        }

        @Test
        void release_recursoInexistenteSeIgnoraPorqueNoHayNadaQueDevolver() {
            iniciarSesionConToken("t");
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

            assertThatCode(() -> client.release(10L, 1L)).doesNotThrowAnyException();
            server.verify();
        }

        @Test
        void errorDelServidorSeTraduceACatalogoNoDisponible() {
            iniciarSesionConToken("t");
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatThrownBy(() -> client.hold(10L, 1L))
                    .isInstanceOf(CatalogUnavailableException.class)
                    .hasMessageContaining("la reserva no cambió de estado");
        }

        @Test
        void rechazoDeSeguridadSeTraduceACatalogoNoDisponible() {
            iniciarSesionConToken("t");
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            assertThatThrownBy(() -> client.hold(10L, 1L)).isInstanceOf(CatalogUnavailableException.class);
            assertThatThrownBy(() -> client.release(10L, 1L)).isInstanceOf(CatalogUnavailableException.class);
        }

        @Test
        void release_errorDelServidorTambienSeTraduceACatalogoNoDisponible() {
            iniciarSesionConToken("t");
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

            assertThatThrownBy(() -> client.release(10L, 1L)).isInstanceOf(CatalogUnavailableException.class);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Cableado real: RestClient de producción (CatalogClientConfig) contra un servidor HTTP de verdad
    // ------------------------------------------------------------------------------------------
    @Nested
    class ConServidorHttpReal {

        private HttpServer server;
        private final List<String> recibidos = new CopyOnWriteArrayList<>();
        private volatile long demoraMs = 0;

        @BeforeEach
        void levantarServidor() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                recibidos.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                        + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
                try {
                    Thread.sleep(demoraMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = OK.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                    // el cliente ya se fue (timeout)
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        @AfterEach
        void apagarServidor() {
            server.stop(0);
        }

        private CatalogStockClient clienteReal(String baseUrl, Duration conexion, Duration lectura) {
            CatalogProperties props = new CatalogProperties(baseUrl, conexion, lectura);
            return new CatalogStockClient(new CatalogClientConfig().catalogRestClient(RestClient.builder(), props));
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Test
        void hold_yRelease_llegaAlServidorConMetodoRutaYJwt() {
            iniciarSesionConToken("jwt-real");
            CatalogStockClient client = clienteReal(baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5));

            client.hold(10L, 1L);
            client.release(10L, 1L);

            assertThat(recibidos).containsExactly(
                    "POST /api/catalog/resources/10/booking-stock/1 auth=Bearer jwt-real",
                    "DELETE /api/catalog/resources/10/booking-stock/1 auth=Bearer jwt-real");
        }

        @Test
        void catalogoApagado_seTraduceACatalogoNoDisponibleRapido() throws IOException {
            int puertoCerrado;
            try (ServerSocket libre = new ServerSocket(0)) {
                puertoCerrado = libre.getLocalPort();
            }
            CatalogStockClient client = clienteReal("http://127.0.0.1:" + puertoCerrado,
                    Duration.ofSeconds(2), Duration.ofSeconds(5));

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> client.hold(10L, 1L)).isInstanceOf(CatalogUnavailableException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(4));
        }

        @Test
        void catalogoLento_cortaPorTimeoutDeLecturaEnVezDeColgarLaRequest() {
            demoraMs = 3_000;
            CatalogStockClient client = clienteReal(baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(300));

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> client.release(10L, 1L)).isInstanceOf(CatalogUnavailableException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
        }
    }
}
