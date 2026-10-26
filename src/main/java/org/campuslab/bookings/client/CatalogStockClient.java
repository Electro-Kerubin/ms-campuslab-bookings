package org.campuslab.bookings.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.campuslab.bookings.exception.CatalogUnavailableException;
import org.campuslab.bookings.exception.InsufficientStockException;
import org.campuslab.bookings.exception.ResourceNotFoundException;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Optional;

/**
 * Mueve el stock de un equipo en ms-campuslab-catalog cuando cambia el estado de una reserva.
 *
 * Llama con el mismo JWT del técnico/admin que está cambiando el estado: el catálogo valida el token y
 * sus roles. Las dos operaciones son idempotentes por reserva en el catálogo, así que reintentar una
 * aprobación que falló a medias no descuenta dos veces.
 */
@Component
@Slf4j
public class CatalogStockClient {

    private static final String BOOKING_STOCK = "/api/catalog/resources/{resourceId}/booking-stock/{bookingId}";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient catalogRestClient;

    public CatalogStockClient(RestClient catalogRestClient) {
        this.catalogRestClient = catalogRestClient;
    }

    /**
     * Toma 1 unidad del recurso para la reserva (se aprobó).
     *
     * @throws InsufficientStockException  el equipo no tiene unidades disponibles
     * @throws ResourceNotFoundException   el recurso no existe en el catálogo
     * @throws CatalogUnavailableException el catálogo no respondió o rechazó la llamada
     */
    public void hold(Long resourceId, Long bookingId) {
        call(HttpMethod.POST, resourceId, bookingId, true);
    }

    /**
     * Devuelve la unidad que la reserva tenía tomada (se devolvió o se canceló después de aprobarse).
     * Si el recurso ya no existe en el catálogo no hay nada que devolver y se ignora.
     *
     * @throws CatalogUnavailableException el catálogo no respondió o rechazó la llamada
     */
    public void release(Long resourceId, Long bookingId) {
        call(HttpMethod.DELETE, resourceId, bookingId, false);
    }

    /**
     * true si el recurso existe en el catálogo. Se usa al CREAR una reserva:
     * antes se aceptaban reservas de recursos inexistentes.
     *
     * @throws CatalogUnavailableException el catálogo no respondió (fail-closed:
     *                                     sin poder validar, no se crea la reserva)
     */
    public boolean exists(Long resourceId) {
        try {
            catalogRestClient.get()
                    .uri("/api/catalog/resources/{resourceId}", resourceId)
                    .headers(headers -> bearerToken().ifPresent(headers::setBearerAuth))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            return false;
        } catch (RestClientException ex) {
            log.error("Catálogo no disponible al validar el recurso {}: {}", resourceId, ex.getMessage());
            throw new CatalogUnavailableException(
                    "No se pudo validar el recurso " + resourceId
                            + " contra el catálogo; la reserva no se creó. Intenta de nuevo en unos minutos.", ex);
        }
    }

    private void call(HttpMethod method, Long resourceId, Long bookingId, boolean failIfResourceMissing) {
        try {
            catalogRestClient.method(method)
                    .uri(BOOKING_STOCK, resourceId, bookingId)
                    .headers(headers -> bearerToken().ifPresent(headers::setBearerAuth))
                    .retrieve()
                    .onStatus(status -> status.value() == 409, (request, response) -> {
                        throw new InsufficientStockException(
                                errorMessage(response, "No hay stock disponible del equipo (recurso " + resourceId + ")"));
                    })
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        if (failIfResourceMissing) {
                            throw new ResourceNotFoundException("El recurso " + resourceId + " no existe en el catálogo");
                        }
                        log.warn("Recurso {} no existe en el catálogo: nada que devolver para la reserva {}", resourceId, bookingId);
                    })
                    .toBodilessEntity();
            log.info("Catálogo atendió {} de stock: recurso={} reserva={}",
                    method == HttpMethod.POST ? "la toma" : "la liberación", resourceId, bookingId);
        } catch (RestClientException ex) {
            // Sin conexión, timeout, 401/403 o 5xx del catálogo: no sabemos en qué quedó el stock
            log.error("Catálogo no disponible al {} stock (recurso={}, reserva={}): {}",
                    method == HttpMethod.POST ? "tomar" : "liberar", resourceId, bookingId, ex.getMessage());
            throw new CatalogUnavailableException(
                    "No se pudo actualizar el stock del equipo en el catálogo; la reserva no cambió de estado. "
                            + "Intenta de nuevo en unos minutos.", ex);
        }
    }

    // El JWT con el que llegó la request original (el técnico/admin que cambia el estado)
    private Optional<String> bearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return Optional.of(jwtAuth.getToken().getTokenValue());
        }
        return Optional.empty();
    }

    // El catálogo responde {"error": "..."}; si el cuerpo no es lo esperado se usa el mensaje por defecto
    private String errorMessage(ClientHttpResponse response, String fallback) {
        try {
            JsonNode body = JSON.readTree(response.getBody());
            if (body != null && body.hasNonNull("error")) {
                return body.get("error").asText();
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("Cuerpo de error del catálogo no legible: {}", ex.getMessage());
        }
        return fallback;
    }
}
