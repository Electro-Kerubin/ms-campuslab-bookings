package org.campuslab.bookings.messaging.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.campuslab.bookings.config.MessagingProperties;
import org.campuslab.bookings.entity.Booking;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Publica comandos a RabbitMQ usando el exchange direct (campuslab.messaging.exchanges.direct).
 * Los consumidores de ms-campuslab-notify reciben estos comandos para:
 * - enviar email al estudiante
 * - crear ticket de preparación al técnico
 * - generar PDF de vale/acta
 *
 * Qué exchange se usa y por qué:
 * <pre>
 *  comando     exchange        routing key                        por qué
 *  email       cmd.direct      email.send                         comando punto a punto: un solo destino, clave exacta
 *  prep        cmd.direct      prep.ticket                        ídem
 *  voucher     cmd.topic       voucher.retiro / voucher.devolucion   el voucher tiene subtipos: la cola voucher los recibe
 *                                                                 todos con el patrón voucher.*, y otra cola puede enlazarse
 *                                                                 solo a uno (p. ej. un archivo de actas con voucher.devolucion)
 * </pre>
 * Exchanges y routing keys vienen de application.yml, no están escritos aquí.
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

        publishDirect(properties.queues().email().directRoutingKey(), payload);
    }

    // Comando: generar ticket de preparación de sala para el técnico
    public void sendPrepTicketCommand(Booking booking) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("resourceId", booking.getResourceId());
        payload.put("startTime", booking.getStartTime().toString());
        payload.put("studentEmail", booking.getStudentEmail());

        publishDirect(properties.queues().prep().directRoutingKey(), payload);
    }

    // Comando: generar PDF (vale de retiro o acta de devolución). Va por el exchange TOPIC con la clave
    // voucher.<subtipo> (VALE_RETIRO -> voucher.retiro, ACTA_DEVOLUCION -> voucher.devolucion).
    public void sendVoucherCommand(Booking booking, String tipoVoucher) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("tipo", tipoVoucher);
        payload.put("studentEmail", booking.getStudentEmail());

        String topicKey = voucherTopicKey(tipoVoucher);
        if (topicKey != null) {
            publish(properties.exchanges().topic(), topicKey, payload);
        } else {
            // El patrón configurado no permite derivar la clave (no termina en ".*"): se usa la clave exacta
            publishDirect(properties.queues().voucher().directRoutingKey(), payload);
        }
    }

    // "voucher.*" + "ACTA_DEVOLUCION" -> "voucher.devolucion" (el prefijo sale del patrón configurado)
    private String voucherTopicKey(String tipoVoucher) {
        String pattern = properties.queues().voucher().topicRoutingKey();
        if (!pattern.endsWith(".*") || tipoVoucher == null || tipoVoucher.isBlank()) {
            return null;
        }
        String subtype = tipoVoucher.substring(tipoVoucher.indexOf('_') + 1).toLowerCase(Locale.ROOT);
        return pattern.substring(0, pattern.length() - 1) + subtype;
    }

    private void publishDirect(String routingKey, Map<String, Object> payload) {
        publish(properties.exchanges().direct(), routingKey, payload);
    }

    // Método común: envuelve el payload en el envelope (type = routing key) y publica al exchange indicado
    //
    // Best-effort: igual que con Kafka, si RabbitMQ no está disponible no
    // debe tumbar el cambio de estado de la reserva que lo disparó.
    private void publish(String exchange, String routingKey, Map<String, Object> payload) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, EventEnvelope.of(routingKey, payload));
            log.info("Comando RabbitMQ enviado a {} con routingKey={}", exchange, routingKey);
        } catch (Exception ex) {
            log.warn("No se pudo enviar comando RabbitMQ a {} con routingKey={}: {}",
                    exchange, routingKey, ex.getMessage());
        }
    }
}
