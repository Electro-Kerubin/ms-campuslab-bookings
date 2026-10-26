package org.campuslab.bookings.messaging.producer;

import org.campuslab.bookings.config.RabbitNamesProperties;
import org.campuslab.bookings.config.RabbitNamesProperties.Exchanges;
import org.campuslab.bookings.config.RabbitNamesProperties.Flow;
import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * El productor publica con el exchange y las routing keys de la configuración
 * (campuslab.rabbit), sin nombres escritos en el código.
 */
class BookingCommandProducerTest {

    // Nombres distintos a los de application.yml para comprobar que salen de la configuración
    private final RabbitNamesProperties names = new RabbitNamesProperties(
            new Exchanges("test.direct", "test.topic", "test.dlx"),
            new Flow("q.test.email", "q.test.email.dlq", "test.email", "test.email.*", "test.email.dlq"),
            new Flow("q.test.prep", "q.test.prep.dlq", "test.prep", "test.prep.#", "test.prep.dlq"),
            new Flow("q.test.voucher", "q.test.voucher.dlq", "test.voucher", "test.voucher.*", "test.voucher.dlq"));

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final BookingCommandProducer producer = new BookingCommandProducer(rabbitTemplate, names);

    private final Booking booking = Booking.builder()
            .id(1L)
            .resourceId(10L)
            .studentEmail("alumno@duoc.cl")
            .startTime(LocalDateTime.of(2026, 10, 24, 10, 0))
            .status(BookingStatus.APROBADA)
            .build();

    @Test
    void publicaElEmailConLaRoutingKeyConfigurada() {
        producer.sendEmailCommand(booking, "Tu reserva fue APROBADA");

        assertThat(publicado("test.email").type()).isEqualTo("test.email");
    }

    @Test
    void publicaElTicketDePreparacionConLaRoutingKeyConfigurada() {
        producer.sendPrepTicketCommand(booking);

        assertThat(publicado("test.prep").payload()).containsEntry("resourceId", 10L);
    }

    @Test
    void publicaElVoucherConLaRoutingKeyConfigurada() {
        producer.sendVoucherCommand(booking, "VALE_RETIRO");

        assertThat(publicado("test.voucher").payload()).containsEntry("tipo", "VALE_RETIRO");
    }

    @Test
    void noPropagaElErrorSiRabbitNoEstaDisponible() {
        doThrow(new AmqpConnectException(new RuntimeException("sin broker")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> producer.sendEmailCommand(booking, "x")).doesNotThrowAnyException();
    }

    private EventEnvelope publicado(String routingKey) {
        ArgumentCaptor<Object> mensaje = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq("test.direct"),
                eq(routingKey), mensaje.capture());
        return (EventEnvelope) mensaje.getValue();
    }
}
