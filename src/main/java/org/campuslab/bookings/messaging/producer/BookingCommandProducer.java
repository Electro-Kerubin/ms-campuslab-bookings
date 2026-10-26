package org.campuslab.bookings.messaging.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.campuslab.bookings.config.RabbitNamesProperties;
import org.campuslab.bookings.entity.Booking;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Publica comandos a RabbitMQ en el exchange direct (campuslab.rabbit.exchanges.direct).
 * Los consumidores de ms-campuslab-notify reciben estos comandos para:
 * - enviar email al estudiante (campuslab.rabbit.email.routing-key)
 * - crear ticket de preparación al técnico (campuslab.rabbit.prep.routing-key)
 * - generar PDF de vale/acta (campuslab.rabbit.voucher.routing-key)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingCommandProducer {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitNamesProperties names;

    // Comando: notificar por email al estudiante un cambio de estado de su reserva
    public void sendEmailCommand(Booking booking, String motivo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("to", booking.getStudentEmail());
        payload.put("status", booking.getStatus().name());
        payload.put("motivo", motivo);

        publish(names.email().routingKey(), payload);
    }

    // Comando: generar ticket de preparación de sala para el técnico
    public void sendPrepTicketCommand(Booking booking) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("resourceId", booking.getResourceId());
        payload.put("startTime", booking.getStartTime().toString());
        payload.put("studentEmail", booking.getStudentEmail());

        publish(names.prep().routingKey(), payload);
    }

    // Comando: generar PDF (vale de retiro o acta de devolución)
    public void sendVoucherCommand(Booking booking, String tipoVoucher) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("tipo", tipoVoucher);
        payload.put("studentEmail", booking.getStudentEmail());

        publish(names.voucher().routingKey(), payload);
    }

    // Método común: envuelve el payload en el envelope (type = routing key) y publica al exchange
    //
    // Best-effort: igual que con Kafka, si RabbitMQ no está disponible no
    // debe tumbar el cambio de estado de la reserva que lo disparó.
    private void publish(String routingKey, Map<String, Object> payload) {
        String exchange = names.exchanges().direct();
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, EventEnvelope.of(routingKey, payload));
            log.info("Comando RabbitMQ enviado a {} con routingKey={}", exchange, routingKey);
        } catch (Exception ex) {
            log.warn("No se pudo enviar comando RabbitMQ a {} con routingKey={}: {}",
                    exchange, routingKey, ex.getMessage());
        }
    }
}
