package org.campuslab.bookings.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.campuslab.bookings.dto.BookingRequest;
import org.campuslab.bookings.dto.BookingResponse;
import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.campuslab.bookings.exception.InvalidStatusTransitionException;
import org.campuslab.bookings.exception.ResourceNotFoundException;
import org.campuslab.bookings.mapper.BookingMapper;
import org.campuslab.bookings.messaging.producer.BookingCommandProducer;
import org.campuslab.bookings.messaging.producer.BookingEventProducer;
import org.campuslab.bookings.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lógica de negocio de reservas:
 * - crear y consultar reservas
 * - cambiar de estado respetando la máquina de estados
 * - publicar eventos a Kafka y comandos a RabbitMQ según lo que pasó
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository repository;
    private final BookingMapper mapper;
    private final BookingEventProducer kafkaProducer;
    private final BookingCommandProducer rabbitProducer;

    // Transiciones válidas de la máquina de estados (quién puede ir a dónde)
    private static final Map<BookingStatus, Set<BookingStatus>> TRANSICIONES_VALIDAS = Map.of(
            BookingStatus.SOLICITADA, EnumSet.of(BookingStatus.APROBADA, BookingStatus.CANCELADA),
            BookingStatus.APROBADA, EnumSet.of(BookingStatus.EN_PREPARACION, BookingStatus.CANCELADA),
            BookingStatus.EN_PREPARACION, EnumSet.of(BookingStatus.EN_USO, BookingStatus.CANCELADA),
            BookingStatus.EN_USO, EnumSet.of(BookingStatus.DEVUELTA),
            BookingStatus.DEVUELTA, EnumSet.noneOf(BookingStatus.class),  // estado final
            BookingStatus.CANCELADA, EnumSet.noneOf(BookingStatus.class)  // estado final
    );

    /**
     * Crea una reserva nueva. Siempre nace en SOLICITADA.
     * El email del estudiante puede venir en el body o tomarse del JWT.
     */
    @Transactional
    public BookingResponse create(BookingRequest request, String emailDesdeJwt) {
        // Validación de negocio: el fin debe ser posterior al inicio
        if (!request.endTime().isAfter(request.startTime())) {
            throw new IllegalArgumentException("endTime debe ser posterior a startTime");
        }

        // Prioridad: lo que envía el body, si no, el email del token
        String studentEmail = request.studentEmail() != null ? request.studentEmail() : emailDesdeJwt;

        Booking booking = mapper.toEntity(request, studentEmail);
        Booking guardada = repository.save(booking);

        // El evento "se solicitó una reserva" alimenta report y audit por Kafka
        kafkaProducer.publishBookingEvent(guardada, BookingStatus.SOLICITADA);

        log.info("Reserva creada id={} para resourceId={}", guardada.getId(), guardada.getResourceId());
        return mapper.toResponse(guardada);
    }

    // Busca una reserva por id o lanza 404
    @Transactional(readOnly = true)
    public BookingResponse getById(Long id) {
        return mapper.toResponse(findOrThrow(id));
    }

    /**
     * Lista con filtros opcionales para GET /api/bookings?status=...&from=...&to=...
     * Los tres filtros son opcionales: si vienen null se ignoran.
     */
    @Transactional(readOnly = true)
    public List<BookingResponse> findByFilters(BookingStatus status, LocalDateTime from, LocalDateTime to) {
        return repository.findByFilters(status, from, to)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    /**
     * Cambia el estado de una reserva validando la máquina de estados.
     * Luego publica el evento en Kafka y dispara los comandos de notificación
     * que correspondan según el nuevo estado.
     */
    @Transactional
    public BookingResponse updateStatus(Long id, BookingStatus nuevoEstado) {
        Booking booking = findOrThrow(id);
        BookingStatus actual = booking.getStatus();

        // Regla central del negocio: no se permiten saltos (ej: SOLICITADA -> EN_USO)
        if (!TRANSICIONES_VALIDAS.get(actual).contains(nuevoEstado)) {
            throw new InvalidStatusTransitionException(actual, nuevoEstado);
        }

        booking.setStatus(nuevoEstado);
        booking.setUpdatedAt(LocalDateTime.now());
        Booking actualizada = repository.save(booking);

        // 1) Evento a Kafka: fuente de verdad para reportería y auditoría
        kafkaProducer.publishBookingEvent(actualizada, nuevoEstado);

        // 2) Comandos a RabbitMQ según el nuevo estado (notificaciones y vouchers)
        switch (nuevoEstado) {
            case APROBADA -> {
                // Nota: aquí ms-catalog debería descontar stock/cupo del recurso
                rabbitProducer.sendEmailCommand(actualizada, "Tu reserva fue APROBADA");
            }
            case EN_PREPARACION -> {
                // El técnico recibe su ticket de preparación de sala/equipo
                rabbitProducer.sendPrepTicketCommand(actualizada);
            }
            case EN_USO -> {
                // Se genera el vale de retiro en PDF
                rabbitProducer.sendVoucherCommand(actualizada, "VALE_RETIRO");
            }
            case DEVUELTA -> {
                rabbitProducer.sendEmailCommand(actualizada, "Devolución registrada. Gracias");
                rabbitProducer.sendVoucherCommand(actualizada, "ACTA_DEVOLUCION");
            }
            case CANCELADA -> rabbitProducer.sendEmailCommand(actualizada, "Tu reserva fue CANCELADA");
            default -> { /* SOLICITADA no aplica aquí: es el estado inicial */ }
        }

        log.info("Reserva {} cambió de {} a {}", id, actual, nuevoEstado);
        return mapper.toResponse(actualizada);
    }

    // Busca la reserva o lanza la excepción que el handler convierte en 404
    private Booking findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reserva no encontrada: id=" + id));
    }
}
