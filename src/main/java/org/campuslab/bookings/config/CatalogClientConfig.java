package org.campuslab.bookings.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Cliente HTTP hacia ms-campuslab-catalog, con timeouts cortos: si el catálogo no responde, la aprobación
 * falla rápido en vez de dejar colgada la request del técnico.
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogClientConfig {

    @Bean
    public RestClient catalogRestClient(RestClient.Builder builder, CatalogProperties properties) {
        // HTTP/1.1: el catálogo es Tomcat en claro; evita el intento de upgrade a h2c del cliente del JDK
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
