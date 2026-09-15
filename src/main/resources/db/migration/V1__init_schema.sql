-- Esquema inicial del microservicio de reservas.
-- Flyway ejecuta este script una sola vez al arrancar el servicio.

CREATE TABLE bookings (
    id            BIGSERIAL PRIMARY KEY,
    resource_id   BIGINT       NOT NULL,               -- id del lab/equipo en ms-catalog
    student_email VARCHAR(255) NOT NULL,               -- estudiante que reserva (del JWT)
    purpose       VARCHAR(500) NOT NULL,               -- motivo académico
    start_time    TIMESTAMP    NOT NULL,               -- inicio de la reserva
    end_time      TIMESTAMP    NOT NULL,               -- fin de la reserva
    status        VARCHAR(30)  NOT NULL,               -- estado de la máquina de estados
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP,

    -- Restricciones de negocio a nivel BD (última línea de defensa)
    CONSTRAINT chk_status CHECK (status IN
        ('SOLICITADA', 'APROBADA', 'EN_PREPARACION', 'EN_USO', 'DEVUELTA', 'CANCELADA')),
    CONSTRAINT chk_fechas CHECK (end_time > start_time)
);

-- Índices para los filtros más usados: por estado y por rango de fechas
CREATE INDEX idx_bookings_status     ON bookings (status);
CREATE INDEX idx_bookings_start_time ON bookings (start_time);
