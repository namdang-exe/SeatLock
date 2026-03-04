package com.seatlock.booking.client;

import com.seatlock.booking.exception.SlotNotFoundException;
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
}
