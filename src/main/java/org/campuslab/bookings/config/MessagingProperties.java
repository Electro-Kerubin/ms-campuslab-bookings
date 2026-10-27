package org.campuslab.bookings.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Nombres de exchanges, colas y routing keys, leídos de application.yml
 * (prefijo campuslab.messaging). Es la única fuente de estos nombres: la topología
 * y el productor los leen de aquí.
 *
 * Es el mismo contrato que usa ms-campuslab-notify (multi-repo: cada servicio tiene su copia
 * y el bloque del application.yml debe ser idéntico en ambos). Si falta algún valor,
 * la aplicación no arranca (validación al bindear).
 */
@Validated
@ConfigurationProperties(prefix = "campuslab.messaging")
public record MessagingProperties(
        @Valid @NotNull Exchanges exchanges,
        @Valid @NotNull Queues queues,
        @Valid @NotNull Retention retention,
        @Valid @NotNull Idempotency idempotency,
        @NotBlank @Pattern(regexp = "classic|quorum", message = "queueType debe ser classic o quorum")
        @DefaultValue("classic") String queueType
) {

    /** true si las colas se declaran de tipo quorum (replicadas entre los nodos del clúster). */
    public boolean quorum() {
        return "quorum".equals(queueType);
    }

    /** direct y topic reciben los comandos; deadLetter recibe los que fallan. */
    public record Exchanges(
            @NotBlank String direct,
            @NotBlank String topic,
            @NotBlank String deadLetter
    ) {
    }

    /** Un flujo (caso de uso) por cola: email, preparación de sala y voucher PDF. */
    public record Queues(
            @Valid @NotNull Flow email,
            @Valid @NotNull Flow prep,
            @Valid @NotNull Flow voucher
    ) {
    }

    /**
     * Un caso de uso: su cola principal, su DLQ y las tres routing keys que la enlazan
     * (direct, topic y la del dead letter exchange).
     */
    public record Flow(
            @NotBlank String name,
            @NotBlank String dlq,
            @NotBlank String directRoutingKey,
            @NotBlank String topicRoutingKey,
            @NotBlank String dlqRoutingKey
    ) {
    }

    /** Políticas de retención (en milisegundos / cantidad de mensajes). */
    public record Retention(
            @Positive int queueTtlMs,
            @Positive int queueMaxLength,
            @Positive int dlqTtlMs
    ) {
    }

    public record Idempotency(@Positive int maxTrackedEvents) {
    }
}
