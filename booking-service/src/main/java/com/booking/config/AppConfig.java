package com.booking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Application configuration.
 * Declares the RestTemplate bean used to call the Catalogue Service.
 * The catalogue base URL is injected from application.properties (or env var).
 */
@Configuration
public class AppConfig {

    /**
     * The base URL of the Catalogue Service.
     * Defaults to localhost for local development; overridden via
     * CATALOGUE_SERVICE_URL environment variable inside Docker.
     */
    @Value("${catalogue.service.url:http://localhost:8080}")
    private String catalogueServiceUrl;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public String catalogueServiceUrl() {
        return catalogueServiceUrl;
    }
}
