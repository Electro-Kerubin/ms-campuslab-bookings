package org.campuslab.bookings.mapper;

import org.campuslab.bookings.dto.BookingRequest;
import org.campuslab.bookings.dto.BookingResponse;
import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Convierte entre la entidad Booking y los DTOs de entrada/salida.
 * Así la API nunca expone directamente la entidad JPA.
 */
@Component
public class BookingMapper {

    // DTO de entrada -> nueva entidad (siempre nace en estado SOLICITADA)
    public Booking toEntity(BookingRequest request, String studentEmail) {
        return Booking.builder()
                .resourceId(request.resourceId())
                .studentEmail(studentEmail)
                .purpose(request.purpose())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .status(BookingStatus.SOLICITADA)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // Entidad -> DTO de salida
    public BookingResponse toResponse(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getResourceId(),
                booking.getStudentEmail(),
                booking.getPurpose(),
                booking.getStartTime(),
                booking.getEndTime(),
                booking.getStatus().name(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }
}
