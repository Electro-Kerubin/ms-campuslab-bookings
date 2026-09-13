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
    public void publishBookingEvent(Booking booking, BookingStatus nuevoEstado) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("resourceId", booking.getResourceId());
        payload.put("studentEmail", booking.getStudentEmail());
        payload.put("status", nuevoEstado.name());
        payload.put("startTime", booking.getStartTime().toString());
        payload.put("endTime", booking.getEndTime().toString());

        EventEnvelope envelope = EventEnvelope.of("BOOKING_" + nuevoEstado.name(), payload);

        // Usamos el bookingId como clave para que todos los eventos
        // de una misma reserva caigan en la misma partición (orden garantizado).
        kafkaTemplate.send(TOPIC, String.valueOf(booking.getId()), envelope);

        log.info("Evento Kafka enviado a {}: type={}, bookingId={}", TOPIC, envelope.type(), booking.getId());
    }
}
