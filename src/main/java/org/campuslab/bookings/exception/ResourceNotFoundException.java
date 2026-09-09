package org.campuslab.bookings.exception;

/**
 * Se lanza cuando no existe la reserva consultada (404).
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
