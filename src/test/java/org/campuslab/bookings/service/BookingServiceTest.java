package org.campuslab.bookings.service;

import org.campuslab.bookings.dto.BookingRequest;
import org.campuslab.bookings.dto.BookingResponse;
import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.campuslab.bookings.exception.InvalidStatusTransitionException;
import org.campuslab.bookings.exception.ResourceNotFoundException;
import org.campuslab.bookings.mapper.BookingMapper;
import org.campuslab.bookings.messaging.producer.BookingCommandProducer;
import org.campuslab.bookings.messaging.producer.BookingEventProducer;
import org.campuslab.bookings.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios de la lógica de negocio de BookingService.
 * Se mockea el repository y los productores de mensajes (no dependemos de BD, Kafka ni Rabbit).
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository repository;

    @Mock
    private BookingEventProducer kafkaProducer;

    @Mock
    private BookingCommandProducer rabbitProducer;

    private BookingService service;

    @BeforeEach
    void setUp() {
        // El mapper va real: es una clase simple sin dependencias externas
        service = new BookingService(repository, new BookingMapper(), kafkaProducer, rabbitProducer);
    }

    // ---------- Helpers para no repetir código en cada test ----------

    private BookingRequest request(String email) {
        return new BookingRequest(
                10L, email, "Práctica de física",
                LocalDateTime.of(2026, 9, 10, 10, 0),
                LocalDateTime.of(2026, 9, 10, 12, 0));
    }

    private Booking reserva(BookingStatus status) {
        return Booking.builder()
                .id(1L)
                .resourceId(10L)
                .studentEmail("juan@uni.cl")
                .purpose("Práctica de física")
                .startTime(LocalDateTime.of(2026, 9, 10, 10, 0))
                .endTime(LocalDateTime.of(2026, 9, 10, 12, 0))
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // El save devuelve la misma entidad que recibe (comportamiento normal del ORM)
    private void mockearSave() {
        when(repository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- CREATE ----------

    @Test
    void crearReserva_naceEnSolicitada_yPublicaEventoKafka() {
        mockearSave();

        BookingResponse respuesta = service.create(request("juan@uni.cl"), null);

        // La reserva siempre nace en SOLICITADA
        assertThat(respuesta.status()).isEqualTo("SOLICITADA");

        // El evento a Kafka debe haberse publicado una vez
        verify(kafkaProducer, times(1)).publishBookingEvent(any(Booking.class), eq(BookingStatus.SOLICITADA));
    }

    @Test
    void crearReserva_siEndTimeEsAnteriorAlStart_lanzaError() {
        BookingRequest mala = new BookingRequest(
                10L, "juan@uni.cl", "Práctica",
                LocalDateTime.of(2026, 9, 10, 12, 0),  // start 12:00
                LocalDateTime.of(2026, 9, 10, 10, 0)); // end 10:00 (¡antes!)

        assertThatThrownBy(() -> service.create(mala, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endTime debe ser posterior a startTime");

        // Si falla la validación, no se toca la BD ni los mensajes
        verifyNoInteractions(repository, kafkaProducer, rabbitProducer);
    }

    @Test
    void crearReserva_sinEmailEnBody_usaElDelJwt() {
        mockearSave();

        service.create(request(null), "pedro@uni.cl");

        // Capturamos la entidad que se mandó a guardar para revisar el email elegido
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStudentEmail()).isEqualTo("pedro@uni.cl");
    }

    // ---------- READ ----------

    @Test
    void obtenerPorId_noExiste_lanza404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listarConFiltros_delegaEnElRepository() {
        Booking booking = reserva(BookingStatus.APROBADA);
        when(repository.findByFilters(BookingStatus.APROBADA, null, null)).thenReturn(List.of(booking));

        List<BookingResponse> resultado = service.findByFilters(BookingStatus.APROBADA, null, null);

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).status()).isEqualTo("APROBADA");
    }

    // ---------- MÁQUINA DE ESTADOS: transiciones válidas ----------

    @Test
    void aprobarReserva_enviaEventoKafka_yEmailAlEstudiante() {
        Booking booking = reserva(BookingStatus.SOLICITADA);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();

        service.updateStatus(1L, BookingStatus.APROBADA);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.APROBADA);
        verify(kafkaProducer).publishBookingEvent(booking, BookingStatus.APROBADA);
        verify(rabbitProducer).sendEmailCommand(booking, "Tu reserva fue APROBADA");
    }

    @Test
    void pasarAEnPreparacion_generaTicketParaElTecnico() {
        Booking booking = reserva(BookingStatus.APROBADA);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();

        service.updateStatus(1L, BookingStatus.EN_PREPARACION);

        verify(rabbitProducer).sendPrepTicketCommand(booking);
    }

    @Test
    void pasarAEnUso_generaValeDeRetiro() {
        Booking booking = reserva(BookingStatus.EN_PREPARACION);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();

        service.updateStatus(1L, BookingStatus.EN_USO);

        verify(rabbitProducer).sendVoucherCommand(booking, "VALE_RETIRO");
    }

    @Test
    void devolverReserva_enviaEmail_yActaDeDevolucion() {
        Booking booking = reserva(BookingStatus.EN_USO);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();

        service.updateStatus(1L, BookingStatus.DEVUELTA);

        verify(rabbitProducer).sendEmailCommand(booking, "Devolución registrada. Gracias");
        verify(rabbitProducer).sendVoucherCommand(booking, "ACTA_DEVOLUCION");
    }

    @Test
    void cancelarReserva_enviaEmail() {
        Booking booking = reserva(BookingStatus.APROBADA);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();

        service.updateStatus(1L, BookingStatus.CANCELADA);

        verify(rabbitProducer).sendEmailCommand(booking, "Tu reserva fue CANCELADA");
    }

    // ---------- MÁQUINA DE ESTADOS: transiciones inválidas (regla clave del negocio) ----------

    @Test
    void noSePuedeSaltarDeSolicitadaAEnUso() {
        // Regla del contexto: no se permite EN_USO sin pasar por APROBADA
        Booking booking = reserva(BookingStatus.SOLICITADA);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(1L, BookingStatus.EN_USO))
                .isInstanceOf(InvalidStatusTransitionException.class);

        // Nada se guarda ni se publica cuando la transición es inválida
        verify(repository, never()).save(any());
        verifyNoInteractions(kafkaProducer, rabbitProducer);
    }

    @Test
    void noSePuedeVolverAtras_deEnUsoAprobada() {
        Booking booking = reserva(BookingStatus.EN_USO);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(1L, BookingStatus.APROBADA))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void estadosTerminales_noPermitenCambios() {
        // DEVUELTA y CANCELADA son finales: probar ambos
        for (BookingStatus terminal : List.of(BookingStatus.DEVUELTA, BookingStatus.CANCELADA)) {
            Booking booking = reserva(terminal);
            when(repository.findById(1L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> service.updateStatus(1L, BookingStatus.APROBADA))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }
    }

    @Test
    void cambiarEstado_deReservaInexistente_lanza404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(99L, BookingStatus.APROBADA))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
