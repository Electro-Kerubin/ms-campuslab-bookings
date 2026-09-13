package org.campuslab.bookings.exception;

import org.campuslab.bookings.entity.BookingStatus;

/**
 * Se lanza cuando se intenta un cambio de estado no permitido
 * por la máquina de estados (ej: SOLICITADA -> EN_USO sin aprobar antes). Da 409.
 */
public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(BookingStatus actual, BookingStatus destino) {
        super("Transición inválida: no se puede pasar de " + actual + " a " + destino
                + ". Flujo válido: SOLICITADA -> APROBADA -> EN_PREPARACION -> EN_USO -> DEVUELTA (o CANCELADA).");
    }
}
