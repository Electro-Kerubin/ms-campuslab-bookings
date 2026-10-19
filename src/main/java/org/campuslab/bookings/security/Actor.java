package org.campuslab.bookings.security;

/**
 * Quién ejecuta una operación y desde dónde. Viaja en los eventos de Kafka para que
 * ms-campuslab-audit pueda responder "quién hizo qué, cuándo y desde dónde".
 *
 * Cualquier campo puede ser null (por ejemplo una operación sin usuario autenticado).
 */
public record Actor(String email, String name, String role, String ip) {

    /** Operación sin un usuario identificable (no hay JWT en el contexto). */
    public static Actor unknown() {
        return new Actor(null, null, null, null);
    }
}
