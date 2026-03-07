package com.seatlock.booking.client;

import com.seatlock.booking.exception.SlotNotFoundException;
import com.seatlock.booking.exception.VenueServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class SlotVerificationClient {

    private final RestClient venueServiceClient;

    public SlotVerificationClient(RestClient venueServiceClient) {
        this.venueServiceClient = venueServiceClient;
    }

    @CircuitBreaker(name = "venue-service", fallbackMethod = "verifyFallback")
    @Retry(name = "venue-http")
    public List<InternalSlotResponse> verify(List<UUID> slotIds) {
        String ids = slotIds.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(","));

        return venueServiceClient.get()
                .uri("/api/v1/internal/slots?ids={ids}", ids)
                .retrieve()
                .onStatus(status -> status.value() == 404,
                        (req, resp) -> { throw new SlotNotFoundException(); })
                .body(new ParameterizedTypeReference<>() {});
    }

    // Fallback: called when circuit is open or all retries are exhausted.
    // SlotNotFoundException is a business error, not a transient fault — rethrow it directly.
    private List<InternalSlotResponse> verifyFallback(List<UUID> slotIds, SlotNotFoundException e) {
        throw e;
    }

    private List<InternalSlotResponse> verifyFallback(List<UUID> slotIds, Exception e) {
        throw new VenueServiceUnavailableException(e);
    }
}
