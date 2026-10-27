package org.campuslab.bookings.config;

import org.campuslab.bookings.config.MessagingProperties.Flow;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declara la topología RabbitMQ del proyecto (exchanges, colas y bindings) de cada caso de uso.
 * Al conectar el servicio, RabbitMQ crea lo que falte de forma idempotente.
 * Los nombres NO están aquí: vienen de application.yml (campuslab.messaging.*),
 * ver {@link MessagingProperties}.
 *
 * Rutas de mensajería (consumidor: ms-campuslab-notify):
 *
 * <pre>
 *  caso de uso   exchange        routing key   cola principal    --(falla/expira)--> DLX --> DLQ
 *  email         cmd.direct      email.send    q.cmd.email                       cmd.dead.dlx  q.cmd.email.dlq   (email.dlq)
 *                cmd.topic       email.*
 *  prep          cmd.direct      prep.ticket   q.cmd.prep                        cmd.dead.dlx  q.cmd.prep.dlq    (prep.dlq)
 *                cmd.topic       prep.#
 *  voucher       cmd.direct      voucher.gen   q.cmd.voucher                     cmd.dead.dlx  q.cmd.voucher.dlq (voucher.dlq)
 *                cmd.topic       voucher.*
 * </pre>
 *
 * OJO: ms-campuslab-notify declara estas mismas colas (RabbitMQConfig). RabbitMQ rechaza
 * redeclarar una cola con argumentos distintos (PRECONDITION_FAILED), así que el bloque
 * campuslab.messaging del application.yml debe ser idéntico en los dos servicios.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitTopologyConfig {

    private final MessagingProperties properties;

    public RabbitTopologyConfig(MessagingProperties properties) {
        this.properties = properties;
    }

    // ---------- Serialización ----------

    // Serializa los mensajes como JSON en vez del formato binario por defecto de Java
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ---------- Exchanges ----------

    @Bean
    public DirectExchange cmdDirect() {
        return new DirectExchange(properties.exchanges().direct());
    }

    @Bean
    public TopicExchange cmdTopic() {
        return new TopicExchange(properties.exchanges().topic());
    }

    // Exchange hacia donde van los mensajes que fallan (dead letter)
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(properties.exchanges().deadLetter());
    }

    // ---------- Colas principales (con DLX configurado) ----------

    // Email/push al estudiante
    @Bean
    public Queue emailQueue() {
        return mainQueue(properties.queues().email());
    }

    // Ticket de preparación de sala/equipo para el técnico
    @Bean
    public Queue prepQueue() {
        return mainQueue(properties.queues().prep());
    }

    // Generación de PDF (vale de retiro / acta de devolución)
    @Bean
    public Queue voucherQueue() {
        return mainQueue(properties.queues().voucher());
    }

    // ---------- Colas DLQ (destino final de los mensajes fallidos) ----------

    @Bean
    public Queue emailDlq() {
        return deadLetterQueue(properties.queues().email());
    }

    @Bean
    public Queue prepDlq() {
        return deadLetterQueue(properties.queues().prep());
    }

    @Bean
    public Queue voucherDlq() {
        return deadLetterQueue(properties.queues().voucher());
    }

    // ---------- Bindings: cada cola se une a los dos exchanges ----------

    // email: binding direct (email.send) y topic (email.*)
    @Bean
    public Binding emailDirectBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdDirect()).with(properties.queues().email().directRoutingKey());
    }

    @Bean
    public Binding emailTopicBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdTopic()).with(properties.queues().email().topicRoutingKey());
    }

    // prep: binding direct (prep.ticket) y topic (prep.#)
    @Bean
    public Binding prepDirectBinding() {
        return BindingBuilder.bind(prepQueue()).to(cmdDirect()).with(properties.queues().prep().directRoutingKey());
    }

    @Bean
    public Binding prepTopicBinding() {
        return BindingBuilder.bind(prepQueue()).to(cmdTopic()).with(properties.queues().prep().topicRoutingKey());
    }

    // voucher: binding direct (voucher.gen) y topic (voucher.*)
    @Bean
    public Binding voucherDirectBinding() {
        return BindingBuilder.bind(voucherQueue()).to(cmdDirect()).with(properties.queues().voucher().directRoutingKey());
    }

    @Bean
    public Binding voucherTopicBinding() {
        return BindingBuilder.bind(voucherQueue()).to(cmdTopic()).with(properties.queues().voucher().topicRoutingKey());
    }

    // DLQs hacia el exchange de dead letters
    @Bean
    public Binding emailDlqBinding() {
        return BindingBuilder.bind(emailDlq()).to(deadLetterExchange()).with(properties.queues().email().dlqRoutingKey());
    }

    @Bean
    public Binding prepDlqBinding() {
        return BindingBuilder.bind(prepDlq()).to(deadLetterExchange()).with(properties.queues().prep().dlqRoutingKey());
    }

    @Bean
    public Binding voucherDlqBinding() {
        return BindingBuilder.bind(voucherDlq()).to(deadLetterExchange()).with(properties.queues().voucher().dlqRoutingKey());
    }

    // ---------- Políticas de retención ----------

    // Cola principal: un comando que nadie consume en 30 s expira y va a la DLQ;
    // si la cola supera los 1000 mensajes, los más antiguos también van a la DLQ.
    // maxLength(int) está deprecado, pero se mantiene a propósito: RabbitMQ compara también el
    // TIPO de cada argumento al redeclarar, y las colas ya existentes se declararon con enteros.
    @SuppressWarnings("deprecation")
    private Queue mainQueue(Flow flow) {
        return durable(flow.name())
                .ttl(properties.retention().queueTtlMs())
                .maxLength(properties.retention().queueMaxLength())
                .deadLetterExchange(properties.exchanges().deadLetter())
                .deadLetterRoutingKey(flow.dlqRoutingKey())
                .build();
    }

    // DLQ: conserva los mensajes fallidos 24 h para análisis y luego los borra.
    private Queue deadLetterQueue(Flow flow) {
        return durable(flow.dlq())
                .ttl(properties.retention().dlqTtlMs())
                .build();
    }

    // Cola durable del tipo configurado (campuslab.messaging.queue-type): classic o quorum
    private QueueBuilder durable(String name) {
        QueueBuilder builder = QueueBuilder.durable(name);
        return properties.quorum() ? builder.quorum() : builder;
    }
}
