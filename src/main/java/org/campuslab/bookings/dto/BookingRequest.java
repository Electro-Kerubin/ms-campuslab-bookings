package org.campuslab.bookings.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

/**
 * Cuerpo del POST /api/bookings para crear una reserva.
 * Solo el estudiante (o técnico en su nombre) envía estos datos.
 */
public record BookingRequest(

        // Id del laboratorio/equipo a reservar (existe en ms-campuslab-catalog)
        @NotNull(message = "resourceId es obligatorio")
        Long resourceId,

        // Email del estudiante. Si viene vacío se toma del JWT en el servicio.
        @Email(message = "studentEmail debe ser un email válido")
        String studentEmail,

        @NotBlank(message = "purpose es obligatorio")
        @Size(max = 500, message = "purpose no puede superar 500 caracteres")
        String purpose,

        @NotNull(message = "startTime es obligatorio")
        LocalDateTime startTime,

        @NotNull(message = "endTime es obligatorio")
        LocalDateTime endTime
) {
}
