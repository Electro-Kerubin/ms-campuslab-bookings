package org.campuslab.bookings.messaging.producer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope (sobre) común que envuelve TODOS los mensajes que salen de este servicio,
 * tanto a Kafka como a RabbitMQ. Es requisito del proyecto:
 * type, eventId, timestamp, traceId, correlationId + payload.
 */
public record EventEnvelope(
        String type,          // qué tipo de mensaje es (ej: "BOOKING_APROBADA", "email.send")
        String eventId,       // id único del mensaje, sirve para idempotencia en los consumidores
        Instant timestamp,    // momento de creación del mensaje
        String traceId,       // id de trazabilidad de la operación completa
        String correlationId, // id que correlaciona request <-> respuesta
        Map<String, Object> payload // los datos del evento en sí
) {
    // Factory para crear el envelope sin repetir boilerplate en cada envío
    public static EventEnvelope of(String type, Map<String, Object> payload) {
        String id = UUID.randomUUID().toString();
        return new EventEnvelope(type, id, Instant.now(), id, id, payload);
    }
}
