package org.campuslab.bookings.config;

import org.campuslab.bookings.config.RabbitNamesProperties.Flow;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declara la topología RabbitMQ del proyecto (exchanges, colas y bindings).
 * Al conectar el servicio, RabbitMQ crea lo que falte de forma idempotente.
 *
 * Los nombres vienen de campuslab.rabbit en application.yml ({@link RabbitNamesProperties}):
 * 3 exchanges (direct, topic y dead letter) y 3 rutas (email, prep, voucher),
 * cada una con su cola principal, su DLQ y sus routing keys.
 */
@Configuration
@EnableConfigurationProperties(RabbitNamesProperties.class)
public class RabbitTopologyConfig {

    private final RabbitNamesProperties names;

    public RabbitTopologyConfig(RabbitNamesProperties names) {
        this.names = names;
    }

    // ---------- Exchanges ----------

    // Serializa los mensajes como JSON en vez del formato binario por defecto de Java
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange cmdDirect() {
        return new DirectExchange(names.exchanges().direct());
    }

    @Bean
    public TopicExchange cmdTopic() {
        return new TopicExchange(names.exchanges().topic());
    }

    // Exchange hacia donde van los mensajes que fallan (dead letter)
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(names.exchanges().deadLetter());
    }

    // ---------- Colas principales (con DLX configurado) ----------
    //
    // OJO: ms-campuslab-notify declara estas mismas colas (RabbitMQConfig). RabbitMQ rechaza
    // redeclarar una cola con argumentos distintos (PRECONDITION_FAILED), así que los
    // argumentos (TTL, largo, DLX, routing keys) deben ser idénticos en los dos servicios.

    // Email/push al estudiante
    @Bean
    public Queue emailQueue() {
        return mainQueue(names.email());
    }

    // Ticket de preparación de sala/equipo para el técnico
    @Bean
    public Queue prepQueue() {
        return mainQueue(names.prep());
    }

    // Generación de PDF (vale de retiro / acta de devolución)
    @Bean
    public Queue voucherQueue() {
        return mainQueue(names.voucher());
    }

    // ---------- Colas DLQ (destino final de los mensajes fallidos) ----------

    @Bean
    public Queue emailDlq() {
        return deadLetterQueue(names.email());
    }

    @Bean
    public Queue prepDlq() {
        return deadLetterQueue(names.prep());
    }

    @Bean
    public Queue voucherDlq() {
        return deadLetterQueue(names.voucher());
    }

    // ---------- Bindings: cada cola se une a los dos exchanges ----------

    // Email: direct con su routing key y topic con su patrón
    @Bean
    public Binding emailDirectBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdDirect()).with(names.email().routingKey());
    }

    @Bean
    public Binding emailTopicBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdTopic()).with(names.email().topicPattern());
    }

    // Preparación: direct con su routing key y topic con su patrón
    @Bean
    public Binding prepDirectBinding() {
        return BindingBuilder.bind(prepQueue()).to(cmdDirect()).with(names.prep().routingKey());
    }

    @Bean
    public Binding prepTopicBinding() {
        return BindingBuilder.bind(prepQueue()).to(cmdTopic()).with(names.prep().topicPattern());
    }

    // Voucher: direct con su routing key y topic con su patrón
    @Bean
    public Binding voucherDirectBinding() {
        return BindingBuilder.bind(voucherQueue()).to(cmdDirect()).with(names.voucher().routingKey());
    }

    @Bean
    public Binding voucherTopicBinding() {
        return BindingBuilder.bind(voucherQueue()).to(cmdTopic()).with(names.voucher().topicPattern());
    }

    // DLQs hacia el exchange de dead letters
    @Bean
    public Binding emailDlqBinding() {
        return BindingBuilder.bind(emailDlq()).to(deadLetterExchange()).with(names.email().deadLetterRoutingKey());
    }

    @Bean
    public Binding prepDlqBinding() {
        return BindingBuilder.bind(prepDlq()).to(deadLetterExchange()).with(names.prep().deadLetterRoutingKey());
    }

    @Bean
    public Binding voucherDlqBinding() {
        return BindingBuilder.bind(voucherDlq()).to(deadLetterExchange()).with(names.voucher().deadLetterRoutingKey());
    }

    // ---------- Políticas de retención ----------

    // Cola principal: un comando que nadie consume en 30 s expira y va a la DLQ;
    // si la cola supera los 1000 mensajes, los más antiguos también van a la DLQ.
    private Queue mainQueue(Flow flow) {
        return QueueBuilder.durable(flow.queue())
                .ttl(30_000)
                .maxLength(1000)
                .deadLetterExchange(names.exchanges().deadLetter())
                .deadLetterRoutingKey(flow.deadLetterRoutingKey())
                .build();
    }

    // DLQ: conserva los mensajes fallidos 24 h para análisis y luego los borra.
    private Queue deadLetterQueue(Flow flow) {
        return QueueBuilder.durable(flow.deadLetterQueue())
                .ttl(86_400_000)
                .build();
    }
}
