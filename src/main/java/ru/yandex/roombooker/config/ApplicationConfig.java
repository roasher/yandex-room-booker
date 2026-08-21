package ru.yandex.roombooker.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    @ConditionalOnMissingBean(Clock.class)
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
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
        requestFactory.setConnectionRequestTimeout(HTTP_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(HTTP_READ_TIMEOUT);
        return restClientBuilder
                .baseUrl(properties.calendarBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
