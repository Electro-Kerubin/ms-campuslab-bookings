package org.campuslab.bookings.messaging.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.campuslab.bookings.entity.Booking;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Publica comandos a RabbitMQ usando el exchange direct "cmd.direct".
 * Los consumidores de ms-campuslab-notify reciben estos comandos para:
 * - enviar email al estudiante (routing key: email.send)
 * - crear ticket de preparación al técnico (routing key: prep.ticket)
 * - generar PDF de vale/acta (routing key: voucher.gen)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingCommandProducer {

    // Exchange direct definido en RabbitTopologyConfig
    public static final String EXCHANGE = "cmd.direct";

    private final RabbitTemplate rabbitTemplate;

    // Comando: notificar por email al estudiante un cambio de estado de su reserva
    public void sendEmailCommand(Booking booking, String motivo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("to", booking.getStudentEmail());
        payload.put("status", booking.getStatus().name());
        payload.put("motivo", motivo);

        publish("email.send", EventEnvelope.of("email.send", payload));
    }

    // Comando: generar ticket de preparación de sala para el técnico
    public void sendPrepTicketCommand(Booking booking) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("resourceId", booking.getResourceId());
        payload.put("startTime", booking.getStartTime().toString());
        payload.put("studentEmail", booking.getStudentEmail());

        publish("prep.ticket", EventEnvelope.of("prep.ticket", payload));
    }

    // Comando: generar PDF (vale de retiro o acta de devolución)
    public void sendVoucherCommand(Booking booking, String tipoVoucher) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("tipo", tipoVoucher);
        payload.put("studentEmail", booking.getStudentEmail());

        publish("voucher.gen", EventEnvelope.of("voucher.gen", payload));
    }

    // Método común: envuelve el payload en el envelope y publica al exchange
    //
    // Best-effort: igual que con Kafka, si RabbitMQ no está disponible no
    // debe tumbar el cambio de estado de la reserva que lo disparó.
    private void publish(String routingKey, EventEnvelope envelope) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE, routingKey, envelope);
            log.info("Comando RabbitMQ enviado a {} con routingKey={}", EXCHANGE, routingKey);
        } catch (Exception ex) {
            log.warn("No se pudo enviar comando RabbitMQ a {} con routingKey={}: {}",
                    EXCHANGE, routingKey, ex.getMessage());
        }
    }
}
