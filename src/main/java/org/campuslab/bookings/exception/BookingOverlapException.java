package org.campuslab.bookings.exception;

/**
 * Ya existe una reserva activa del mismo recurso cuyo horario se solapa
 * con el de la reserva que se intenta crear (-> HTTP 409).
 */
public class BookingOverlapException extends RuntimeException {
    public BookingOverlapException(String message) {
        super(message);
    }
}
