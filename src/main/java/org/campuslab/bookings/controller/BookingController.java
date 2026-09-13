package org.campuslab.bookings.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.campuslab.bookings.dto.BookingRequest;
import org.campuslab.bookings.dto.BookingResponse;
import org.campuslab.bookings.dto.StatusUpdateRequest;
import org.campuslab.bookings.entity.BookingStatus;
import org.campuslab.bookings.service.BookingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API REST del microservicio de reservas.
 *
 * Endpoints:
 *   POST /api/bookings                  -> crear reserva (estudiante/técnico/admin)
 *   GET  /api/bookings/{id}             -> consultar una reserva
 *   PUT  /api/bookings/{id}/status      -> cambiar estado (solo técnico/admin)
 *   GET  /api/bookings?status=&from=&to=-> listar con filtros opcionales
 */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;

    // Crea una reserva. El email del estudiante sale del JWT si no viene en el body.
    @PostMapping
    @PreAuthorize("hasAnyRole('ESTUDIANTE','TECNICO','ADMIN')")
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody BookingRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        String emailDesdeJwt = extraerEmail(jwt);
        BookingResponse creada = service.create(request, emailDesdeJwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(creada);
    }

    // Consulta una reserva por id
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    // Lista con filtros opcionales: /api/bookings?status=APROBADA&from=2026-09-01T00:00&to=2026-09-30T23:59
    @GetMapping
    public ResponseEntity<List<BookingResponse>> findByFilters(
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(service.findByFilters(status, from, to));
    }

    // Cambio de estado: solo el técnico o el admin pueden aprobar/preparar/devolver
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('TECNICO','ADMIN')")
    public ResponseEntity<BookingResponse> updateStatus(@PathVariable Long id,
                                                        @Valid @RequestBody StatusUpdateRequest request) {
        // Convertimos el string del body al enum; si es inválido lanza IllegalArgumentException (400)
        BookingStatus nuevoEstado = BookingStatus.valueOf(request.status().trim().toUpperCase());
        return ResponseEntity.ok(service.updateStatus(id, nuevoEstado));
    }

    // Azure AD pone el correo en "email" o en "preferred_username" según la configuración
    private String extraerEmail(Jwt jwt) {
        if (jwt == null) return null;
        if (jwt.hasClaim("email")) return jwt.getClaimAsString("email");
        return jwt.getClaimAsString("preferred_username");
    }
}
