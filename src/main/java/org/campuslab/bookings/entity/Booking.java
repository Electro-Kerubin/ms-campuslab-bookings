package org.campuslab.bookings.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entidad principal: representa una reserva de laboratorio/equipo.
 * Se mapea a la tabla "bookings" en PostgreSQL (ver migración Flyway V1).
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Identificador del recurso reservado (lab/equipo) en ms-campuslab-catalog
    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    // Email del estudiante que solicita la reserva (viene del JWT de Azure AD)
    @Column(name = "student_email", nullable = false)
    private String studentEmail;

    // Motivo académico de la reserva (ej: práctica de física)
    @Column(name = "purpose", nullable = false, length = 500)
    private String purpose;

    // Inicio y fin de la reserva
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    // Estado actual dentro de la máquina de estados
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BookingStatus status;

    // Auditoría básica: cuándo se creó y actualizó el registro
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
