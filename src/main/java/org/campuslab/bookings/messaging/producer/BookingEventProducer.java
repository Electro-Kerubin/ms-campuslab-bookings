package org.campuslab.bookings.messaging.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Publica eventos de reserva al tópico Kafka "bookings.events".
 * Esos eventos son la fuente de verdad para ms-report y ms-audit,
 * por eso se envían DESPUÉS de guardar el cambio en la base de datos.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingEventProducer {

    public static final String TOPIC = "bookings.events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Publica un evento del tipo "BOOKING_<NUEVO_ESTADO>" con los datos de la reserva
    //
    // Best-effort: si Kafka no está disponible (dev local sin el broker
    // levantado, o una caída puntual), NO debe tumbar la operación de
    // negocio que lo dispara (crear/cambiar estado de una reserva). Kafka
    // es la fuente de verdad para report/audit, no para bookings mismo.
    public void publishBookingEvent(Booking booking, BookingStatus nuevoEstado) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("resourceId", booking.getResourceId());
        payload.put("studentEmail", booking.getStudentEmail());
        payload.put("status", nuevoEstado.name());
        payload.put("startTime", booking.getStartTime().toString());
        payload.put("endTime", booking.getEndTime().toString());

        EventEnvelope envelope = EventEnvelope.of("BOOKING_" + nuevoEstado.name(), payload);

        try {
            // Usamos el bookingId como clave para que todos los eventos
            // de una misma reserva caigan en la misma partición (orden garantizado).
            kafkaTemplate.send(TOPIC, String.valueOf(booking.getId()), envelope)
                    .exceptionally(ex -> {
                        log.warn("No se pudo publicar evento Kafka (bookingId={}, type={}): {}",
                                booking.getId(), envelope.type(), ex.getMessage());
                        return null;
                    });
            log.info("Evento Kafka encolado en {}: type={}, bookingId={}", TOPIC, envelope.type(), booking.getId());
        } catch (Exception ex) {
            // send() puede lanzar de forma síncrona (ej: no logra obtener
            // metadata del cluster dentro de max.block.ms).
            log.warn("No se pudo encolar evento Kafka (bookingId={}, type={}): {}",
                    booking.getId(), envelope.type(), ex.getMessage());
        }
    }
}
