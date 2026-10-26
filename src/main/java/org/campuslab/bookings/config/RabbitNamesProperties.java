package org.campuslab.bookings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Nombres de exchanges, colas y routing keys de RabbitMQ, leídos desde
 * {@code campuslab.rabbit} en application.yml. Es la única fuente de esos nombres
 * en el servicio: la topología y el productor de comandos los toman de aquí.
 *
 * ms-campuslab-notify tiene el mismo bloque y la misma clase, para que los dos
 * servicios declaren y usen exactamente la misma topología.
 */
@ConfigurationProperties(prefix = "campuslab.rabbit")
public record RabbitNamesProperties(Exchanges exchanges, Flow email, Flow prep, Flow voucher) {

    public RabbitNamesProperties {
        Assert.notNull(exchanges, "Falta campuslab.rabbit.exchanges");
        Assert.notNull(email, "Falta campuslab.rabbit.email");
        Assert.notNull(prep, "Falta campuslab.rabbit.prep");
        Assert.notNull(voucher, "Falta campuslab.rabbit.voucher");
    }

    /** Exchanges de comandos: direct, topic y el de dead letters (DLX). */
    public record Exchanges(String direct, String topic, String deadLetter) {

        public Exchanges {
            Assert.hasText(direct, "Falta campuslab.rabbit.exchanges.direct");
            Assert.hasText(topic, "Falta campuslab.rabbit.exchanges.topic");
            Assert.hasText(deadLetter, "Falta campuslab.rabbit.exchanges.dead-letter");
        }
    }

    /**
     * Una ruta de mensajería completa (un caso de uso): su cola, su DLQ, la routing key
     * del exchange direct, el patrón del exchange topic y la routing key hacia la DLQ.
     */
    public record Flow(
            String queue,
            String deadLetterQueue,
            String routingKey,
            String topicPattern,
            String deadLetterRoutingKey) {

        public Flow {
            Assert.hasText(queue, "Falta el nombre de la cola (queue)");
            Assert.hasText(deadLetterQueue, "Falta el nombre de la DLQ (dead-letter-queue) de " + queue);
            Assert.hasText(routingKey, "Falta la routing key (routing-key) de " + queue);
            Assert.hasText(topicPattern, "Falta el patrón topic (topic-pattern) de " + queue);
            Assert.hasText(deadLetterRoutingKey, "Falta la routing key de DLQ (dead-letter-routing-key) de " + queue);
        }
    }
}
