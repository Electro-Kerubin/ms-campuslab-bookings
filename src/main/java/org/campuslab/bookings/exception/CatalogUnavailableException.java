package org.campuslab.bookings.exception;

/**
 * No se pudo mover el stock en ms-campuslab-catalog (caído, lento o rechazó la llamada). El cambio de estado
 * de la reserva se revierte para no dejar el stock y la reserva desalineados. Se responde 503.
 */
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
