package org.campuslab.bookings.client;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Cliente HTTP hacia ms-campuslab-catalog.
 *
 * Se usa para:
 * - validar que el resourceId de una reserva nueva exista realmente en el
 *   catálogo (antes se aceptaban reservas de recursos inexistentes)
 * - mover el stock del equipo cuando la reserva se aprueba (toma 1 unidad)
 *   o se devuelve/cancela (la libera), usando los endpoints idempotentes
 *   de booking-stock del catálogo.
 *
 * Reenvía el Authorization (Bearer JWT) de la request entrante para que
 * los @PreAuthorize del catálogo (ADMIN/TECNICO) se evalúen igual que si
 * el usuario hubiera llamado directo: los roles viajan en el token.
 */
@Component
@Slf4j
public class CatalogClient {

    private final RestClient restClient;

    public CatalogClient(@Value("${campuslab.catalog.url:http://localhost:8082}") String catalogUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(catalogUrl)
                .requestInterceptor((request, body, execution) -> {
                    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                        HttpServletRequest incoming = attributes.getRequest();
                        String authorization = incoming.getHeader(HttpHeaders.AUTHORIZATION);
                        if (authorization != null) {
                            request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
                        }
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * true si el recurso existe en el catálogo.
     * Si el catálogo está caído lanza IllegalStateException (503): crear una
     * reserva sin poder validar el recurso dejaría pasar basura (fail-closed).
     */
    public boolean resourceExists(Long resourceId) {
        try {
            restClient.get()
                    .uri("/api/catalog/resources/{id}", resourceId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "No se pudo validar el recurso " + resourceId + ": catálogo no disponible", e);
        }
    }

    /**
     * Reserva aprobada -> toma 1 unidad del stock del recurso. Idempotente
     * por bookingId en el catálogo. Best-effort: si el catálogo no responde
     * no debe tumbar el cambio de estado (queda registrado en el log).
     */
    public void holdStock(Long resourceId, Long bookingId) {
        try {
            restClient.post()
                    .uri("/api/catalog/resources/{id}/booking-stock/{bookingId}", resourceId, bookingId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Stock tomado para la reserva {} del recurso {}", bookingId, resourceId);
        } catch (Exception e) {
            log.warn("No se pudo tomar stock para la reserva {} del recurso {}: {}",
                    bookingId, resourceId, e.getMessage());
        }
    }

    /**
     * Reserva devuelta o cancelada -> libera la unidad. Idempotente.
     * Best-effort, igual que holdStock.
     */
    public void releaseStock(Long resourceId, Long bookingId) {
        try {
            restClient.delete()
                    .uri("/api/catalog/resources/{id}/booking-stock/{bookingId}", resourceId, bookingId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Stock liberado para la reserva {} del recurso {}", bookingId, resourceId);
        } catch (Exception e) {
            log.warn("No se pudo liberar stock para la reserva {} del recurso {}: {}",
                    bookingId, resourceId, e.getMessage());
        }
    }
}
