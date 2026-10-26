package org.campuslab.bookings.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Formato REAL en el que el evento viaja por Kafka (mismo JsonSerializer que usa la aplicación).
 * ms-campuslab-audit lo consume: si este formato cambia, hay que ajustar su consumidor.
 */
class EventEnvelopeWireFormatTest {

    @Test
    void elEventoViajaComoJsonConLosCamposQueConsumeAudit() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", 7L);
        payload.put("resourceId", 3L);
        payload.put("status", "APROBADA");
        payload.put("actorEmail", "tec@duocuc.cl");
        payload.put("actorName", "Rodrigo Muñoz");
        payload.put("actorRole", "TECNICO");
        payload.put("clientIp", "200.1.2.3");
        EventEnvelope envelope = EventEnvelope.of("BOOKING_APROBADA", payload);

        try (JsonSerializer<EventEnvelope> serializer = new JsonSerializer<>()) {
            String json = new String(serializer.serialize("bookings.events", envelope), StandardCharsets.UTF_8);
            System.out.println("WIRE-FORMAT: " + json);

            JsonNode tree = new ObjectMapper().readTree(json);
            assertThat(tree.get("type").asText()).isEqualTo("BOOKING_APROBADA");
            assertThat(tree.get("eventId").asText()).isNotBlank();
            assertThat(tree.get("traceId").asText()).isNotBlank();
            assertThat(tree.get("correlationId").asText()).isNotBlank();
            assertThat(tree.has("timestamp")).isTrue();
            assertThat(tree.get("payload").get("bookingId").asLong()).isEqualTo(7L);
            assertThat(tree.get("payload").get("actorRole").asText()).isEqualTo("TECNICO");
            assertThat(tree.get("payload").get("clientIp").asText()).isEqualTo("200.1.2.3");
        }
    }
}
