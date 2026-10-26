package org.campuslab.bookings.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.campuslab.bookings.security.Actor;
import org.campuslab.bookings.security.ActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** El evento que consume ms-campuslab-audit lleva la reserva Y quién ejecutó la acción. */
@SuppressWarnings("unchecked")
class BookingEventProducerTest {

    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ActorProvider actorProvider = mock(ActorProvider.class);
    private BookingEventProducer producer;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));
        producer = new BookingEventProducer(kafkaTemplate, actorProvider);
    }

    @Test
    void elEventoIncluyeAQuienLoEjecutoYDesdeDonde() {
        when(actorProvider.current()).thenReturn(new Actor("tec@duocuc.cl", "Rodrigo Muñoz", "TECNICO", "200.1.2.3"));

        producer.publishBookingEvent(reserva(), BookingStatus.APROBADA);

        EventEnvelope envelope = enviado();
        assertThat(envelope.type()).isEqualTo("BOOKING_APROBADA");
        assertThat(envelope.eventId()).isNotBlank();
        assertThat(envelope.payload())
                .containsEntry("bookingId", 7L)
                .containsEntry("resourceId", 3L)
                .containsEntry("studentEmail", "alumno@duocuc.cl")
                .containsEntry("status", "APROBADA")
                .containsEntry("actorEmail", "tec@duocuc.cl")
                .containsEntry("actorName", "Rodrigo Muñoz")
                .containsEntry("actorRole", "TECNICO")
                .containsEntry("clientIp", "200.1.2.3");
    }

    @Test
    void laClaveEsElBookingIdParaConservarElOrdenPorReserva() {
        when(actorProvider.current()).thenReturn(Actor.unknown());

        producer.publishBookingEvent(reserva(), BookingStatus.SOLICITADA);

        verify(kafkaTemplate).send(eq("bookings.events"), eq("7"), any());
    }

    @Test
    void siNoSePuedeIdentificarAlUsuarioElEventoSaleIgualConActorDesconocido() {
        when(actorProvider.current()).thenThrow(new IllegalStateException("sin contexto"));

        assertThatCode(() -> producer.publishBookingEvent(reserva(), BookingStatus.CANCELADA)).doesNotThrowAnyException();

        assertThat(enviado().payload()).containsEntry("actorEmail", null).containsEntry("status", "CANCELADA");
    }

    @Test
    void siKafkaFallaLaOperacionDeNegocioNoSeCae() {
        when(actorProvider.current()).thenReturn(Actor.unknown());
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("broker caído"));

        assertThatCode(() -> producer.publishBookingEvent(reserva(), BookingStatus.APROBADA)).doesNotThrowAnyException();
    }

    private EventEnvelope enviado() {
        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("bookings.events"), anyString(), message.capture());
        return (EventEnvelope) message.getValue();
    }

    private static Booking reserva() {
        return Booking.builder()
                .id(7L).resourceId(3L).studentEmail("alumno@duocuc.cl").status(BookingStatus.SOLICITADA)
                .startTime(LocalDateTime.of(2026, 10, 20, 10, 0)).endTime(LocalDateTime.of(2026, 10, 20, 12, 0))
                .build();
    }
}
