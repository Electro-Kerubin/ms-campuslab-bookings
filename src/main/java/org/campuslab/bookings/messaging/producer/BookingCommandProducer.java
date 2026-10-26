package org.campuslab.bookings.messaging.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.campuslab.bookings.config.MessagingProperties;
import org.campuslab.bookings.entity.Booking;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Publica comandos a RabbitMQ usando el exchange direct (campuslab.messaging.exchanges.direct).
 * Los consumidores de ms-campuslab-notify reciben estos comandos para:
 * - enviar email al estudiante (routing key de la cola email)
 * - crear ticket de preparación al técnico (routing key de la cola prep)
 * - generar PDF de vale/acta (routing key de la cola voucher)
 * Exchange y routing keys vienen de application.yml, no están escritos aquí.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingCommandProducer {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    // Comando: notificar por email al estudiante un cambio de estado de su reserva
    public void sendEmailCommand(Booking booking, String motivo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("to", booking.getStudentEmail());
        payload.put("status", booking.getStatus().name());
        payload.put("motivo", motivo);

        publish(properties.queues().email().directRoutingKey(), payload);
    }

    // Comando: generar ticket de preparación de sala para el técnico
    public void sendPrepTicketCommand(Booking booking) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("resourceId", booking.getResourceId());
        payload.put("startTime", booking.getStartTime().toString());
        payload.put("studentEmail", booking.getStudentEmail());

        publish(properties.queues().prep().directRoutingKey(), payload);
    }

    // Comando: generar PDF (vale de retiro o acta de devolución)
    public void sendVoucherCommand(Booking booking, String tipoVoucher) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("tipo", tipoVoucher);
        payload.put("studentEmail", booking.getStudentEmail());

        publish(properties.queues().voucher().directRoutingKey(), payload);
    }

    // Método común: envuelve el payload en el envelope (type = routing key) y publica al exchange
    //
    // Best-effort: igual que con Kafka, si RabbitMQ no está disponible no
    // debe tumbar el cambio de estado de la reserva que lo disparó.
    private void publish(String routingKey, Map<String, Object> payload) {
        String exchange = properties.exchanges().direct();
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, EventEnvelope.of(routingKey, payload));
            log.info("Comando RabbitMQ enviado a {} con routingKey={}", exchange, routingKey);
        } catch (Exception ex) {
            log.warn("No se pudo enviar comando RabbitMQ a {} con routingKey={}: {}",
                    exchange, routingKey, ex.getMessage());
        }
    }
}
