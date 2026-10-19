package org.campuslab.bookings.exception;

/**
 * El equipo reservado no tiene unidades disponibles: la reserva no se puede aprobar. Se responde 409.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
