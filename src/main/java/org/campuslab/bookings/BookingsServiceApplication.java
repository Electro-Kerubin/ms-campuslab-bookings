package org.campuslab.bookings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bookings Service Application
 *
 * Punto de entrada para el microservicio de gestión de reservas.
 *
 * Responsabilidades:
 * - CRUD de reservas de laboratorios y equipos
 * - Gestión de estados de reserva (SOLICITADA, APROBADA, EN_PREPARACIÓN, EN_USO, DEVUELTA, CANCELADA)
 * - Coordinación de stock y cupo de recursos
 * - Publicación de eventos a Kafka (bookings.events)
 * - Publicación de comandos a RabbitMQ (email, preparación, voucher)
 * - Persistencia en PostgreSQL
 */
// Nota: sin @ComponentScan explícito. @SpringBootApplication ya escanea este paquete,
// y el scan manual rompería los filtros de los tests de slice (@WebMvcTest).
@SpringBootApplication
public class BookingsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingsServiceApplication.class, args);
    }

}
