package org.campuslab.bookings.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo del PUT /api/bookings/{id}/status para cambiar de estado.
 */
public record StatusUpdateRequest(

        @NotNull(message = "status es obligatorio")
        String status
) {
}
