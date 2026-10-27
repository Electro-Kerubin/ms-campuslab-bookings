package org.campuslab.bookings.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.campuslab.bookings.config.MessagingProperties.Exchanges;
import org.campuslab.bookings.config.MessagingProperties.Flow;
import org.campuslab.bookings.config.MessagingProperties.Idempotency;
import org.campuslab.bookings.config.MessagingProperties.Queues;
import org.campuslab.bookings.config.MessagingProperties.Retention;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Topología de bookings: sale solo de las propiedades y coincide con el contrato que declara notify. */
class RabbitTopologyConfigTest {

    private static final Flow EMAIL = new Flow("q.a", "q.a.dlq", "a.direct", "a.*", "a.dlq");
    private static final Flow PREP = new Flow("q.b", "q.b.dlq", "b.direct", "b.#", "b.dlq");
    private static final Flow VOUCHER = new Flow("q.c", "q.c.dlq", "c.direct", "c.*", "c.dlq");

    private final RabbitTopologyConfig config = new RabbitTopologyConfig(new MessagingProperties(
            new Exchanges("x.direct", "x.topic", "x.dead"),
            new Queues(EMAIL, PREP, VOUCHER),
            new Retention(1234, 99, 5678),
            new Idempotency(10), "classic"));

    @Test
    void losExchangesTomanSuNombreDeLasPropiedades() {
        assertThat(config.cmdDirect().getName()).isEqualTo("x.direct");
        assertThat(config.cmdTopic().getName()).isEqualTo("x.topic");
        assertThat(config.deadLetterExchange().getName()).isEqualTo("x.dead");
    }

    @Test
    void cadaColaPrincipalEsDurableYEnviaSusFallidosALaDlq() {
        assertMainQueue(config.emailQueue(), EMAIL);
        assertMainQueue(config.prepQueue(), PREP);
        assertMainQueue(config.voucherQueue(), VOUCHER);
    }

    @Test
    void cadaDlqEsDurableYConservaLosMensajesElTiempoConfigurado() {
        assertDeadLetterQueue(config.emailDlq(), "q.a.dlq");
        assertDeadLetterQueue(config.prepDlq(), "q.b.dlq");
        assertDeadLetterQueue(config.voucherDlq(), "q.c.dlq");
    }

    @Test
    void cadaCasoDeUsoTieneBindingDirectTopicYDeDlq() {
        assertBinding(config.emailDirectBinding(), "q.a", "x.direct", "a.direct");
        assertBinding(config.emailTopicBinding(), "q.a", "x.topic", "a.*");
        assertBinding(config.emailDlqBinding(), "q.a.dlq", "x.dead", "a.dlq");

        assertBinding(config.prepDirectBinding(), "q.b", "x.direct", "b.direct");
        assertBinding(config.prepTopicBinding(), "q.b", "x.topic", "b.#");
        assertBinding(config.prepDlqBinding(), "q.b.dlq", "x.dead", "b.dlq");

        assertBinding(config.voucherDirectBinding(), "q.c", "x.direct", "c.direct");
        assertBinding(config.voucherTopicBinding(), "q.c", "x.topic", "c.*");
        assertBinding(config.voucherDlqBinding(), "q.c.dlq", "x.dead", "c.dlq");
    }

    /**
     * Con el application.yml REAL: nombres del contrato compartido con notify. Si alguien cambia
     * uno solo de los dos yml, RabbitMQ rechazaría la redeclaración (PRECONDITION_FAILED).
     */
    @Test
    void elApplicationYmlDeclaraLaTopologiaAcordadaConNotify() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RabbitTopologyConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(Exchange.class)).hasSize(3);
                    assertThat(context.getBeansOfType(Queue.class).values())
                            .extracting(Queue::getName)
                            .containsExactlyInAnyOrder(
                                    "q.cmd.email", "q.cmd.prep", "q.cmd.voucher",
                                    "q.cmd.email.dlq", "q.cmd.prep.dlq", "q.cmd.voucher.dlq");
                    assertThat(context.getBeansOfType(Binding.class)).hasSize(9);

                    MessagingProperties props = context.getBean(MessagingProperties.class);
                    assertThat(props.exchanges().direct()).isEqualTo("cmd.direct");
                    assertThat(props.queues().email().directRoutingKey()).isEqualTo("email.send");
                    assertThat(props.queues().prep().directRoutingKey()).isEqualTo("prep.ticket");
                    assertThat(props.queues().voucher().directRoutingKey()).isEqualTo("voucher.gen");
                    assertThat(props.retention().queueTtlMs()).isEqualTo(30_000);
                    assertThat(props.retention().dlqTtlMs()).isEqualTo(86_400_000);
                });
    }

    private static void assertMainQueue(Queue queue, Flow flow) {
        assertThat(queue.getName()).isEqualTo(flow.name());
        assertThat(queue.isDurable()).isTrue();
        Map<String, Object> args = queue.getArguments();
        assertThat(args)
                .containsEntry("x-message-ttl", 1234)
                .containsEntry("x-max-length", 99)
                .containsEntry("x-dead-letter-exchange", "x.dead")
                .containsEntry("x-dead-letter-routing-key", flow.dlqRoutingKey());
    }

    private static void assertDeadLetterQueue(Queue queue, String name) {
        assertThat(queue.getName()).isEqualTo(name);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-message-ttl", 5678);
    }

    private static void assertBinding(Binding binding, String queue, String exchange, String routingKey) {
        assertThat(binding.getDestinationType()).isEqualTo(DestinationType.QUEUE);
        assertThat(binding.getDestination()).isEqualTo(queue);
        assertThat(binding.getExchange()).isEqualTo(exchange);
        assertThat(binding.getRoutingKey()).isEqualTo(routingKey);
    }

    // ---------- Alta disponibilidad: tipo de cola y varios nodos (idéntico a notify) ----------

    @org.springframework.boot.context.properties.EnableConfigurationProperties(
            org.springframework.boot.autoconfigure.amqp.RabbitProperties.class)
    static class RabbitPropertiesConfig {
    }

    private ApplicationContextRunner realYml() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RabbitTopologyConfig.class);
    }

    @Test
    void porDefectoLasColasSonClassicYSinArgumentoDeTipo() {
        realYml().run(context -> {
            assertThat(context.getBean(MessagingProperties.class).quorum()).isFalse();
            context.getBeansOfType(Queue.class).values()
                    .forEach(q -> assertThat(q.getArguments()).doesNotContainKey("x-queue-type"));
        });
    }

    @Test
    void conQueueTypeQuorumTodasLasColasIncluidasLasDlqSeDeclaranQuorum() {
        realYml().withPropertyValues("campuslab.messaging.queue-type=quorum").run(context -> {
            assertThat(context.getBeansOfType(Queue.class)).hasSize(6);
            context.getBeansOfType(Queue.class).values()
                    .forEach(q -> assertThat(q.getArguments()).containsEntry("x-queue-type", "quorum"));
        });
    }

    @Test
    void unTipoDeColaInvalidoImpideArrancar() {
        realYml().withPropertyValues("campuslab.messaging.queue-type=mirrored")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void conRabbitmqAddressesElClienteConoceTodosLosNodosDelClusterEnOrden() {
        realYml().withUserConfiguration(RabbitPropertiesConfig.class)
                .withPropertyValues("spring.rabbitmq.addresses=nodo1:5672,nodo2:5672")
                .run(context -> assertThat(context.getBean(org.springframework.boot.autoconfigure.amqp.RabbitProperties.class)
                        .determineAddresses()).isEqualTo("nodo1:5672,nodo2:5672"));
    }

    @Test
    void sinAddressesElClienteUsaHostYPuertoPorDefecto() {
        realYml().withUserConfiguration(RabbitPropertiesConfig.class).run(context ->
                // sin addresses, el cliente usa host:puerto (los valores dependen de RABBITMQ_HOST/RABBITMQ_PORT)
                assertThat(context.getBean(org.springframework.boot.autoconfigure.amqp.RabbitProperties.class))
                        .satisfies(p -> assertThat(p.determineAddresses()).isEqualTo(p.getHost() + ":" + p.getPort())));
    }
}
