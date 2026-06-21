package ru.yandex.roombooker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring beans for HTTP clients and configuration binding.
 */
@Configuration
@EnableConfigurationProperties(RoomBookerProperties.class)
public class ApplicationConfig {

    @Bean
    RestClient calendarRestClient(RoomBookerProperties properties, RestClient.Builder restClientBuilder) {
        return restClientBuilder
                .baseUrl(properties.apiBaseUrl())
                .build();
    }
}
