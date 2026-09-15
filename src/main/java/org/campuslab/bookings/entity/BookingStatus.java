package org.campuslab.bookings.entity;

/**
 * Estados posibles de una reserva.
 *
 * Flujo válido (máquina de estados):
 * SOLICITADA -> APROBADA -> EN_PREPARACION -> EN_USO -> DEVUELTA
 * Cualquier estado (excepto DEVUELTA/CANCELADA) puede pasar a CANCELADA.
 */
public enum BookingStatus {
    SOLICITADA,
    APROBADA,
    EN_PREPARACION,
    EN_USO,
    DEVUELTA,
    CANCELADA
}
