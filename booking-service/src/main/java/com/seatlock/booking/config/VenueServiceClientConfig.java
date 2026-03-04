package com.seatlock.booking.config;

import com.seatlock.booking.security.ServiceJwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class VenueServiceClientConfig {

    @Bean
    public RestClient venueServiceClient(
            @Value("${seatlock.venue-service.base-url}") String baseUrl,
            ServiceJwtService serviceJwtService) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("Authorization", "Bearer " + serviceJwtService.generateToken());
                    return execution.execute(request, body);
                })
                .build();
    }
}
