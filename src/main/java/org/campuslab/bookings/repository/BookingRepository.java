package org.campuslab.bookings.repository;

import org.campuslab.bookings.entity.Booking;
import org.campuslab.bookings.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Acceso a la tabla "bookings" con Spring Data JPA.
 * No escribimos SQL: Spring genera las consultas a partir de nombres de métodos.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Busca reservas aplicando filtros opcionales (para GET /api/bookings?status=...&from=...&to=...).
     * Si un parámetro viene null, ese filtro simplemente no aplica.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE (:status IS NULL OR b.status = :status)
              AND (:from IS NULL OR b.startTime >= :from)
              AND (:to IS NULL OR b.endTime <= :to)
            ORDER BY b.startTime DESC
            """)
    List<Booking> findByFilters(@Param("status") BookingStatus status,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);
}
