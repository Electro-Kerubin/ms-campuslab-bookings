package org.campuslab.bookings.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.campuslab.bookings.config.MessagingProperties;
import org.campuslab.bookings.config.MessagingProperties.Exchanges;
import org.campuslab.bookings.config.MessagingProperties.Flow;
import org.campuslab.bookings.config.MessagingProperties.Idempotency;
import org.campuslab.bookings.config.MessagingProperties.Queues;
import org.campuslab.bookings.config.MessagingProperties.Retention;
import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** El productor publica donde dice la configuración, con el envelope común y sin romper el flujo de negocio. */
class BookingCommandProducerTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private BookingCommandProducer producer;

    @BeforeEach
    void setUp() {
        // nombres inventados a propósito: prueban que el productor no tiene ninguno escrito a mano
        MessagingProperties properties = new MessagingProperties(
                new Exchanges("x.direct", "x.topic", "x.dead"),
                new Queues(
                        new Flow("q.a", "q.a.dlq", "a.send", "a.*", "a.dlq"),
                        new Flow("q.b", "q.b.dlq", "b.ticket", "b.#", "b.dlq"),
                        new Flow("q.c", "q.c.dlq", "c.gen", "c.*", "c.dlq")),
                new Retention(1, 1, 1),
                new Idempotency(1), "classic");
        producer = new BookingCommandProducer(rabbitTemplate, properties);
    }

    @Test
    void elComandoDeEmailVaAlExchangeDirectConLaRoutingKeyConfigurada() {
        producer.sendEmailCommand(reserva(), "Tu reserva fue APROBADA");

        EventEnvelope envelope = published("x.direct", "a.send");
        assertThat(envelope.type()).isEqualTo("a.send");
        assertThat(envelope.eventId()).isNotBlank();
        assertThat(envelope.timestamp()).isNotNull();
        assertThat(envelope.payload())
                .containsEntry("bookingId", 7L)
                .containsEntry("to", "alumno@duocuc.cl")
                .containsEntry("status", "APROBADA")
                .containsEntry("motivo", "Tu reserva fue APROBADA");
    }

    @Test
    void elComandoDePreparacionVaConLaRoutingKeyDeLaColaPrep() {
        producer.sendPrepTicketCommand(reserva());

        assertThat(published("x.direct", "b.ticket").payload())
                .containsEntry("bookingId", 7L)
                .containsEntry("resourceId", 3L);
    }

    // Email y preparación son comandos punto a punto: exchange DIRECT con la clave exacta del caso de uso.
    @Test
    void emailYPreparacionVanPorElExchangeDirect() {
        producer.sendEmailCommand(reserva(), "x");
        producer.sendPrepTicketCommand(reserva());

        verify(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("x.direct"),
                org.mockito.ArgumentMatchers.eq("a.send"), any(Object.class));
        verify(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("x.direct"),
                org.mockito.ArgumentMatchers.eq("b.ticket"), any(Object.class));
        verify(rabbitTemplate, org.mockito.Mockito.never()).convertAndSend(
                org.mockito.ArgumentMatchers.eq("x.topic"), anyString(), any(Object.class));
    }

    // El voucher tiene subtipos: va por el exchange TOPIC con la clave <prefijo del patrón>.<subtipo>.
    @Test
    void elValeDeRetiroVaPorElExchangeTopicConLaClaveDelSubtipo() {
        producer.sendVoucherCommand(reserva(), "VALE_RETIRO");

        EventEnvelope envelope = published("x.topic", "c.retiro");
        assertThat(envelope.type()).isEqualTo("c.retiro");
        assertThat(envelope.payload())
                .containsEntry("bookingId", 7L)
                .containsEntry("tipo", "VALE_RETIRO");
    }

    @Test
    void elActaDeDevolucionVaPorElExchangeTopicConSuPropiaClave() {
        producer.sendVoucherCommand(reserva(), "ACTA_DEVOLUCION");

        assertThat(published("x.topic", "c.devolucion").payload()).containsEntry("tipo", "ACTA_DEVOLUCION");
    }

    @Test
    void laClaveTopicSeCalzaConElPatronDeLaColaVoucher() {
        // Si el patrón de la cola no cubriera la clave que publica el productor, el comando se perdería
        for (String tipo : new String[] {"VALE_RETIRO", "ACTA_DEVOLUCION"}) {
            producer.sendVoucherCommand(reserva(), tipo);
        }
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate, org.mockito.Mockito.times(2)).convertAndSend(
                org.mockito.ArgumentMatchers.eq("x.topic"), keys.capture(), any(Object.class));
        // "c.*" = prefijo + exactamente una palabra
        assertThat(keys.getAllValues()).allMatch(k -> k.matches("c\\.[^.]+"));
    }

    @Test
    void siElPatronNoPermiteDerivarLaClaveSeUsaElDirectConLaClaveExacta() {
        MessagingProperties conPatronAbierto = new MessagingProperties(
                new Exchanges("x.direct", "x.topic", "x.dead"),
                new Queues(
                        new Flow("q.a", "q.a.dlq", "a.send", "a.*", "a.dlq"),
                        new Flow("q.b", "q.b.dlq", "b.ticket", "b.#", "b.dlq"),
                        new Flow("q.c", "q.c.dlq", "c.gen", "c.#", "c.dlq")),
                new Retention(1, 1, 1),
                new Idempotency(1), "classic");
        new BookingCommandProducer(rabbitTemplate, conPatronAbierto).sendVoucherCommand(reserva(), "VALE_RETIRO");

        assertThat(published("x.direct", "c.gen").payload()).containsEntry("tipo", "VALE_RETIRO");
    }

    @Test
    void siRabbitMqNoEstaDisponibleElCambioDeEstadoDeLaReservaNoFalla() {
        doThrow(new IllegalStateException("broker caído"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> producer.sendEmailCommand(reserva(), "x")).doesNotThrowAnyException();
    }

    private EventEnvelope published(String exchange, String routingKey) {
        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq(exchange),
                org.mockito.ArgumentMatchers.eq(routingKey), message.capture());
        return (EventEnvelope) message.getValue();
    }

    private static Booking reserva() {
        return Booking.builder()
                .id(7L)
                .resourceId(3L)
                .studentEmail("alumno@duocuc.cl")
                .status(BookingStatus.APROBADA)
                .startTime(LocalDateTime.of(2026, 10, 20, 10, 0))
                .endTime(LocalDateTime.of(2026, 10, 20, 12, 0))
                .build();
    }
}
