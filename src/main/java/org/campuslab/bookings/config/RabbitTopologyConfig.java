package org.campuslab.bookings.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declara la topología RabbitMQ del proyecto (exchanges, colas y bindings).
 * Al conectar el servicio, RabbitMQ crea lo que falte de forma idempotente.
 *
 * Exchanges: cmd.direct, cmd.topic y cmd.dead.dlx (para mensajes fallidos).
 * Colas: 3 principales + 3 DLQ, según la tabla del contexto del proyecto.
 */
@Configuration
public class RabbitTopologyConfig {

    // ---------- Exchanges ----------

    // Serializa los mensajes como JSON en vez del formato binario por defecto de Java
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange cmdDirect() {
        return new DirectExchange("cmd.direct");
    }

    @Bean
    public TopicExchange cmdTopic() {
        return new TopicExchange("cmd.topic");
    }

    // Exchange hacia donde van los mensajes que fallan (dead letter)
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange("cmd.dead.dlx");
    }

    // ---------- Colas principales (con DLX configurado) ----------
    //
    // OJO: ms-campuslab-notify declara estas mismas colas (RabbitMQConfig). RabbitMQ rechaza
    // redeclarar una cola con argumentos distintos (PRECONDITION_FAILED), así que los
    // argumentos (TTL, largo, DLX, routing keys) deben ser idénticos en los dos servicios.

    // Email/push al estudiante
    @Bean
    public Queue emailQueue() {
        return mainQueue("q.cmd.email", "email.dlq");
    }

    // Ticket de preparación de sala/equipo para el técnico
    @Bean
    public Queue prepQueue() {
        return mainQueue("q.cmd.prep", "prep.dlq");
    }

    // Generación de PDF (vale de retiro / acta de devolución)
    @Bean
    public Queue voucherQueue() {
        return mainQueue("q.cmd.voucher", "voucher.dlq");
    }

    // ---------- Colas DLQ (destino final de los mensajes fallidos) ----------

    @Bean
    public Queue emailDlq() {
        return deadLetterQueue("q.cmd.email.dlq");
    }

    @Bean
    public Queue prepDlq() {
        return deadLetterQueue("q.cmd.prep.dlq");
    }

    @Bean
    public Queue voucherDlq() {
        return deadLetterQueue("q.cmd.voucher.dlq");
    }

    // ---------- Bindings: cada cola se une a los dos exchanges ----------

    // q.cmd.email: binding direct con "email.send" y topic con "email.*"
    @Bean
    public Binding emailDirectBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdDirect()).with("email.send");
    }

    @Bean
    public Binding emailTopicBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdTopic()).with("email.*");
    }

    // q.cmd.prep: binding direct con "prep.ticket" y topic con "prep.#"
    @Bean
    public Binding prepDirectBinding() {
        return BindingBuilder.bind(prepQueue()).to(cmdDirect()).with("prep.ticket");
    }

    @Bean
    public Binding prepTopicBinding() {
        return BindingBuilder.bind(prepQueue()).to(cmdTopic()).with("prep.#");
    }

    // q.cmd.voucher: binding direct con "voucher.gen" y topic con "voucher.*"
    @Bean
    public Binding voucherDirectBinding() {
        return BindingBuilder.bind(voucherQueue()).to(cmdDirect()).with("voucher.gen");
    }

    @Bean
    public Binding voucherTopicBinding() {
        return BindingBuilder.bind(voucherQueue()).to(cmdTopic()).with("voucher.*");
    }

    // DLQs hacia el exchange de dead letters
    @Bean
    public Binding emailDlqBinding() {
        return BindingBuilder.bind(emailDlq()).to(deadLetterExchange()).with("email.dlq");
    }

    @Bean
    public Binding prepDlqBinding() {
        return BindingBuilder.bind(prepDlq()).to(deadLetterExchange()).with("prep.dlq");
    }

    @Bean
    public Binding voucherDlqBinding() {
        return BindingBuilder.bind(voucherDlq()).to(deadLetterExchange()).with("voucher.dlq");
    }

    // ---------- Políticas de retención ----------

    // Cola principal: un comando que nadie consume en 30 s expira y va a la DLQ;
    // si la cola supera los 1000 mensajes, los más antiguos también van a la DLQ.
    private Queue mainQueue(String name, String deadLetterRoutingKey) {
        return QueueBuilder.durable(name)
                .ttl(30_000)
                .maxLength(1000)
                .deadLetterExchange("cmd.dead.dlx")
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    // DLQ: conserva los mensajes fallidos 24 h para análisis y luego los borra.
    private Queue deadLetterQueue(String name) {
        return QueueBuilder.durable(name)
                .ttl(86_400_000)
                .build();
    }
}
