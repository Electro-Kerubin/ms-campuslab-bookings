package org.campuslab.bookings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cómo llegar a ms-campuslab-catalog para mover el stock de los equipos reservados
 * (campuslab.catalog.* en application.yml).
 */
@ConfigurationProperties(prefix = "campuslab.catalog")
public record CatalogProperties(
        @DefaultValue("http://localhost:8082") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout
) {
}
