package org.campuslab.bookings.dto;

import java.time.LocalDateTime;

/**
 * Respuesta estándar al consultar o crear una reserva.
 * Nunca exponemos la entidad JPA directamente.
 */
public record BookingResponse(
        Long id,
        Long resourceId,
        String studentEmail,
        String purpose,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
