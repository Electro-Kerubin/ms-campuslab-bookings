package org.campuslab.bookings.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifica que los nombres de la topología RabbitMQ se leen desde application.yml
 * y que el servicio no arranca si falta alguno.
 */
class RabbitNamesPropertiesTest {

    @Test
    void enlazaLosNombresDesdeApplicationYml() throws IOException {
        RabbitNamesProperties names = bind(new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0));

        assertThat(names.exchanges().direct()).isEqualTo("cmd.direct");
        assertThat(names.exchanges().topic()).isEqualTo("cmd.topic");
        assertThat(names.exchanges().deadLetter()).isEqualTo("cmd.dead.dlx");

        assertThat(names.email().queue()).isEqualTo("q.cmd.email");
        assertThat(names.email().deadLetterQueue()).isEqualTo("q.cmd.email.dlq");
        assertThat(names.email().routingKey()).isEqualTo("email.send");
        assertThat(names.email().topicPattern()).isEqualTo("email.*");
        assertThat(names.email().deadLetterRoutingKey()).isEqualTo("email.dlq");

        assertThat(names.prep().queue()).isEqualTo("q.cmd.prep");
        assertThat(names.prep().deadLetterQueue()).isEqualTo("q.cmd.prep.dlq");
        assertThat(names.prep().routingKey()).isEqualTo("prep.ticket");
        assertThat(names.prep().topicPattern()).isEqualTo("prep.#");
        assertThat(names.prep().deadLetterRoutingKey()).isEqualTo("prep.dlq");

        assertThat(names.voucher().queue()).isEqualTo("q.cmd.voucher");
        assertThat(names.voucher().deadLetterQueue()).isEqualTo("q.cmd.voucher.dlq");
        assertThat(names.voucher().routingKey()).isEqualTo("voucher.gen");
        assertThat(names.voucher().topicPattern()).isEqualTo("voucher.*");
        assertThat(names.voucher().deadLetterRoutingKey()).isEqualTo("voucher.dlq");
    }

    @Test
    void fallaSiFaltaUnNombre() {
        Map<String, Object> incompleto = Map.of(
                "campuslab.rabbit.exchanges.direct", "cmd.direct",
                "campuslab.rabbit.exchanges.topic", "cmd.topic");

        assertThatThrownBy(() -> bind(new MapPropertySource("test", incompleto)))
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining("Falta campuslab.rabbit.exchanges.dead-letter");
    }

    private static RabbitNamesProperties bind(PropertySource<?> source) {
        return new Binder(ConfigurationPropertySources.from(source))
                .bindOrCreate("campuslab.rabbit", RabbitNamesProperties.class);
    }
}
