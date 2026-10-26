package org.campuslab.bookings.service;

import org.campuslab.bookings.client.CatalogStockClient;
import org.campuslab.bookings.dto.BookingRequest;
import org.campuslab.bookings.dto.BookingResponse;
import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.campuslab.bookings.exception.CatalogUnavailableException;
import org.campuslab.bookings.exception.InsufficientStockException;
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
import org.mockito.InOrder;
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

    @Mock
    private CatalogStockClient stockClient;

    private BookingService service;

    @BeforeEach
    void setUp() {
        // El mapper va real: es una clase simple sin dependencias externas
        org.mockito.Mockito.lenient().when(stockClient.exists(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        service = new BookingService(repository, new BookingMapper(), kafkaProducer, rabbitProducer, stockClient);
    }

    // ---------- Helpers para no repetir código en cada test ----------

    private BookingRequest request(String email) {
        return new BookingRequest(
                10L, email, "Práctica de física",
                LocalDateTime.now().plusDays(1).withHour(10).withMinute(0),
                LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
    }

    private Booking reserva(BookingStatus status) {
        return Booking.builder()
                .id(1L)
                .resourceId(10L)
                .studentEmail("juan@uni.cl")
                .purpose("Práctica de física")
                .startTime(LocalDateTime.now().plusDays(1).withHour(10).withMinute(0))
                .endTime(LocalDateTime.now().plusDays(1).withHour(12).withMinute(0))
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
                LocalDateTime.now().plusDays(1).withHour(12).withMinute(0),  // start 12:00
                LocalDateTime.now().plusDays(1).withHour(10).withMinute(0)); // end 10:00 (¡antes!)

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

    // ---------- STOCK DEL EQUIPO (ms-catalog): se toma al aprobar y se devuelve al devolver / cancelar ----------

    @Test
    void aprobarReserva_tomaUnaUnidadDelEquipoAntesDeAvisarANadie() {
        Booking booking = reserva(BookingStatus.SOLICITADA);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();

        service.updateStatus(1L, BookingStatus.APROBADA);

        // Primero el stock, después el evento y el correo: si el stock falla, no sale nada
        InOrder orden = inOrder(stockClient, kafkaProducer, rabbitProducer);
        orden.verify(stockClient).hold(10L, 1L);
        orden.verify(kafkaProducer).publishBookingEvent(booking, BookingStatus.APROBADA);
        orden.verify(rabbitProducer).sendEmailCommand(booking, "Tu reserva fue APROBADA");
        verify(stockClient, never()).release(any(), any());
    }

    @Test
    void aprobarReserva_sinStock_noSeApruebaYNoSalenEventosNiCorreos() {
        Booking booking = reserva(BookingStatus.SOLICITADA);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();
        doThrow(new InsufficientStockException("No hay stock disponible de 'Microscopio' (0 de 3)"))
                .when(stockClient).hold(10L, 1L);

        assertThatThrownBy(() -> service.updateStatus(1L, BookingStatus.APROBADA))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("No hay stock disponible");

        // La excepción sale del método @Transactional: Spring revierte el cambio de estado
        verifyNoInteractions(kafkaProducer, rabbitProducer);
    }

    @Test
    void aprobarReserva_conElCatalogoCaido_noSeApruebaYNoSalenEventosNiCorreos() {
        Booking booking = reserva(BookingStatus.SOLICITADA);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();
        doThrow(new CatalogUnavailableException("catálogo caído", new RuntimeException("connection refused")))
                .when(stockClient).hold(10L, 1L);

        assertThatThrownBy(() -> service.updateStatus(1L, BookingStatus.APROBADA))
                .isInstanceOf(CatalogUnavailableException.class);

        verifyNoInteractions(kafkaProducer, rabbitProducer);
    }

    @Test
    void devolverReserva_devuelveLaUnidadDelEquipo() {
        Booking booking = reserva(BookingStatus.EN_USO);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();

        service.updateStatus(1L, BookingStatus.DEVUELTA);

        verify(stockClient).release(10L, 1L);
        verify(stockClient, never()).hold(any(), any());
    }

    @Test
    void devolverReserva_conElCatalogoCaido_noSeRegistraLaDevolucion() {
        Booking booking = reserva(BookingStatus.EN_USO);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();
        doThrow(new CatalogUnavailableException("catálogo caído", new RuntimeException("timeout")))
                .when(stockClient).release(10L, 1L);

        assertThatThrownBy(() -> service.updateStatus(1L, BookingStatus.DEVUELTA))
                .isInstanceOf(CatalogUnavailableException.class);

        verifyNoInteractions(kafkaProducer, rabbitProducer);
    }

    @Test
    void cancelarReservaYaAprobada_devuelveLaUnidadQueTenia() {
        for (BookingStatus conStockTomado : List.of(BookingStatus.APROBADA, BookingStatus.EN_PREPARACION)) {
            reset(stockClient);
            Booking booking = reserva(conStockTomado);
            when(repository.findById(1L)).thenReturn(Optional.of(booking));
            mockearSave();

            service.updateStatus(1L, BookingStatus.CANCELADA);

            verify(stockClient).release(10L, 1L);
        }
    }

    @Test
    void cancelarReservaSolicitada_noTocaElStockPorqueNuncaTomoNada() {
        Booking booking = reserva(BookingStatus.SOLICITADA);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        mockearSave();

        service.updateStatus(1L, BookingStatus.CANCELADA);

        verifyNoInteractions(stockClient);
        verify(rabbitProducer).sendEmailCommand(booking, "Tu reserva fue CANCELADA");
    }

    @Test
    void pasosIntermedios_noTocanElStock() {
        // APROBADA -> EN_PREPARACION -> EN_USO: la unidad ya está tomada, no hay nada que mover
        Booking aprobada = reserva(BookingStatus.APROBADA);
        when(repository.findById(1L)).thenReturn(Optional.of(aprobada));
        mockearSave();
        service.updateStatus(1L, BookingStatus.EN_PREPARACION);

        service.updateStatus(1L, BookingStatus.EN_USO);

        verifyNoInteractions(stockClient);
    }

    @Test
    void crearReserva_noTocaElStockHastaQueSeApruebe() {
        mockearSave();

        service.create(request("juan@uni.cl"), null);

        verifyNoInteractions(stockClient);
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
        verifyNoInteractions(kafkaProducer, rabbitProducer, stockClient);
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


