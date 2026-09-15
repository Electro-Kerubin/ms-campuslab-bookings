-- Modelo normalizado (3FN) integrado desde main (hotfix: modelo de datos).
-- V1 mantiene la tabla bookings simple que usan las entidades actuales;
-- esta migracion agrega las tablas complementarias del modelo normalizado.

-- Usuarios autenticados vía Azure AD (JWT). external_id = claim "oid" del token.
CREATE TABLE app_users (
    id          BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(100) NOT NULL,
    email       VARCHAR(180) NOT NULL,
    full_name   VARCHAR(180) NOT NULL,
    role        VARCHAR(20) NOT NULL
        CHECK (role IN ('ADMIN', 'TECNICO', 'ESTUDIANTE', 'AUDITOR')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_users_external_id UNIQUE (external_id),
    CONSTRAINT uq_app_users_email UNIQUE (email)
);

-- Equipos/insumos solicitados dentro de una reserva.
-- resource_id referencia catalog.resources.id (otro microservicio/BD: sin FK física).
CREATE TABLE booking_items (
    id            BIGSERIAL PRIMARY KEY,
    booking_id    BIGINT NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    resource_id   BIGINT NOT NULL,
    resource_type VARCHAR(20) NOT NULL
        CHECK (resource_type IN ('EQUIPO', 'INSUMO')),
    quantity      INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
    CONSTRAINT uq_booking_items_booking_resource UNIQUE (booking_id, resource_id)
);

CREATE INDEX idx_booking_items_booking_id ON booking_items (booking_id);

-- Timeline de cambios de estado de una reserva (auditoría local; también se publica a Kafka).
CREATE TABLE booking_status_history (
    id         BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    status     VARCHAR(20) NOT NULL
        CHECK (status IN ('SOLICITADA', 'APROBADA', 'EN_PREPARACION', 'EN_USO', 'DEVUELTA', 'CANCELADA')),
    changed_by BIGINT NOT NULL REFERENCES app_users (id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    comment    VARCHAR(255)
);

CREATE INDEX idx_booking_status_history_booking_id ON booking_status_history (booking_id);
