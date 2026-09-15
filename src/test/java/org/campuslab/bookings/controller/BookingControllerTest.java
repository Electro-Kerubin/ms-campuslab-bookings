package org.campuslab.bookings.controller;

import org.campuslab.bookings.dto.BookingResponse;
import org.campuslab.bookings.entity.BookingStatus;
import org.campuslab.bookings.exception.InvalidStatusTransitionException;
import org.campuslab.bookings.exception.ResourceNotFoundException;
import org.campuslab.bookings.security.SecurityConfig;
import org.campuslab.bookings.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests de la capa web (controller + seguridad + manejo de errores).
 * No levantan BD ni mensajería: el service va mockeado.
 *
 * El post-processor jwt() de spring-security-test simula un token de Azure AD.
 * Ojo: jwt() NO ejecuta el converter de SecurityConfig (claim "roles" -> ROLE_*),
 * por eso en cada test se indican las authorities directamente, igual a como
 * las produciría el converter en producción.
 */
@WebMvcTest(BookingController.class)
@Import({SecurityConfig.class, BookingControllerTest.FakeJwtDecoderConfig.class})
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService service;

    /**
     * La SecurityConfig exige un JwtDecoder para armar la cadena de filtros.
     * En estos tests nunca se invoca de verdad (jwt() inyecta la autenticación directamente),
     * así que basta con un decoder que lance excepción si alguien lo llama.
     */
    @TestConfiguration
    static class FakeJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new IllegalStateException("Los tests deben autenticarse con jwt()");
            };
        }
    }

    // ---------- Helpers ----------

    // Respuesta de ejemplo que devuelven los mocks del service
    private BookingResponse respuestaEjemplo() {
        return new BookingResponse(
                1L, 10L, "juan@uni.cl", "Práctica de física",
                LocalDateTime.of(2026, 9, 10, 10, 0),
                LocalDateTime.of(2026, 9, 10, 12, 0),
                "SOLICITADA",
                LocalDateTime.of(2026, 9, 9, 9, 0),
                LocalDateTime.of(2026, 9, 9, 9, 0));
    }

    // ---------- Seguridad ----------

    @Test
    void obtenerPorId_sinToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/bookings/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void obtenerPorId_conJwtValido_retorna200() throws Exception {
        when(service.getById(1L)).thenReturn(respuestaEjemplo());

        mockMvc.perform(get("/api/bookings/1")
                        .with(jwt())) // cualquier usuario autenticado puede consultar
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("SOLICITADA"));
    }

    // ---------- POST /api/bookings ----------

    @Test
    void crearConRolEstudiante_retorna201() throws Exception {
        when(service.create(any(), eq("juan@uni.cl"))).thenReturn(respuestaEjemplo());

        mockMvc.perform(post("/api/bookings")
                        .with(jwt().jwt(t -> t.claim("email", "juan@uni.cl"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ESTUDIANTE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resourceId": 10,
                                  "purpose": "Práctica de física",
                                  "startTime": "2026-09-10T10:00:00",
                                  "endTime": "2026-09-10T12:00:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SOLICITADA"));
    }

    @Test
    void crearSinRolNoPermitido_retorna403() throws Exception {
        // Un AUDITOR está autenticado pero no puede crear reservas
        mockMvc.perform(post("/api/bookings")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDITOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resourceId": 10,
                                  "purpose": "Práctica",
                                  "startTime": "2026-09-10T10:00:00",
                                  "endTime": "2026-09-10T12:00:00"
                                }
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void crearConBodyIncompleto_retorna400ConDetallePorCampo() throws Exception {
        // Faltan resourceId, purpose y fechas: la validación @NotNull/@NotBlank debe fallar
        mockMvc.perform(post("/api/bookings")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ESTUDIANTE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.resourceId").exists())
                .andExpect(jsonPath("$.errors.purpose").exists())
                .andExpect(jsonPath("$.errors.startTime").exists())
                .andExpect(jsonPath("$.errors.endTime").exists());
    }

    @Test
    void crearConFechasInvalidas_retorna400() throws Exception {
        // Regla de negocio del service: end debe ser posterior a start
        when(service.create(any(), any()))
                .thenThrow(new IllegalArgumentException("endTime debe ser posterior a startTime"));

        mockMvc.perform(post("/api/bookings")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ESTUDIANTE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resourceId": 10,
                                  "purpose": "Práctica",
                                  "startTime": "2026-09-10T12:00:00",
                                  "endTime": "2026-09-10T10:00:00"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---------- PUT /api/bookings/{id}/status ----------

    @Test
    void cambiarEstado_conRolTecnico_retorna200() throws Exception {
        BookingResponse aprobada = respuestaEjemplo();
        when(service.updateStatus(1L, BookingStatus.APROBADA)).thenReturn(aprobada);

        mockMvc.perform(put("/api/bookings/1/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECNICO")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"APROBADA\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void cambiarEstado_conRolEstudiante_retorna403() throws Exception {
        // El estudiante no puede aprobar reservas
        mockMvc.perform(put("/api/bookings/1/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ESTUDIANTE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"APROBADA\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void cambiarEstado_conTransicionInvalida_retorna409() throws Exception {
        when(service.updateStatus(1L, BookingStatus.EN_USO))
                .thenThrow(new InvalidStatusTransitionException(BookingStatus.SOLICITADA, BookingStatus.EN_USO));

        mockMvc.perform(put("/api/bookings/1/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECNICO")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"EN_USO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void cambiarEstado_reservaInexistente_retorna404() throws Exception {
        when(service.updateStatus(99L, BookingStatus.APROBADA))
                .thenThrow(new ResourceNotFoundException("Reserva no encontrada: id=99"));

        mockMvc.perform(put("/api/bookings/99/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECNICO")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"APROBADA\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cambiarEstado_conEstadoInexistente_retorna400() throws Exception {
        // "FOO" no es un estado del enum: no debería llegar al service
        mockMvc.perform(put("/api/bookings/1/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECNICO")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FOO\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    // ---------- GET /api/bookings?status=&from=&to= ----------

    @Test
    void listarConFiltros_retornaLista() throws Exception {
        when(service.findByFilters(BookingStatus.APROBADA, null, null))
                .thenReturn(List.of(respuestaEjemplo()));

        mockMvc.perform(get("/api/bookings")
                        .param("status", "APROBADA")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].status").value("SOLICITADA"));
    }

    // ---------- Rutas fuera de la API ----------

    @Test
    @WithMockUser // usuario autenticado genérico
    void rutaFueraDeApiBookings_retornaDenegado() throws Exception {
        // La SecurityConfig deniega cualquier ruta que no sea /api/bookings/**
        mockMvc.perform(get("/otra-ruta"))
                .andExpect(status().isForbidden());
    }
}
