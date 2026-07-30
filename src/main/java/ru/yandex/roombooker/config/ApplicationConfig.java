package ru.yandex.roombooker.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring beans for HTTP clients and configuration binding.
 */
@Configuration
@EnableConfigurationProperties(RoomCatalogProperties.class)
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "room-booker")
    @ConditionalOnProperty(prefix = "room-booker", name = "booking-mode", havingValue = "api", matchIfMissing = true)
    ApiRoomBookerProperties apiRoomBookerProperties() {
        return new ApiRoomBookerProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "room-booker")
    @ConditionalOnProperty(prefix = "room-booker", name = "booking-mode", havingValue = "browser")
    BrowserRoomBookerProperties browserRoomBookerProperties() {
        return new BrowserRoomBookerProperties();
    }

    @Bean
    RestClient calendarRestClient(RoomBookerProperties properties, RestClient.Builder restClientBuilder) {
        // HttpURLConnection (Boot default) is unreliable for PATCH with a JSON body.
        return restClientBuilder
                .baseUrl(properties.calendarBaseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
                .build();
    }
}
