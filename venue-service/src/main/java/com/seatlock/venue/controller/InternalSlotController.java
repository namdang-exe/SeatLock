package com.seatlock.venue.controller;

import com.seatlock.common.exception.SlotNotFoundException;
import com.seatlock.venue.domain.Slot;
import com.seatlock.venue.domain.Venue;
import com.seatlock.venue.dto.InternalSlotResponse;
import com.seatlock.venue.repository.SlotRepository;
import com.seatlock.venue.repository.VenueRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/internal")
public class InternalSlotController {

    private final SlotRepository slotRepository;
    private final VenueRepository venueRepository;

    public InternalSlotController(SlotRepository slotRepository, VenueRepository venueRepository) {
        this.slotRepository = slotRepository;
        this.venueRepository = venueRepository;
    }

    @GetMapping("/slots")
    public List<InternalSlotResponse> getSlotsByIds(@RequestParam List<UUID> ids) {
        List<Slot> slots = slotRepository.findAllById(ids);
        if (slots.size() != ids.size()) {
            throw new SlotNotFoundException("One or more requested slots not found");
        }

        Set<UUID> venueIds = slots.stream()
                .map(Slot::getVenueId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> venueNames = venueRepository.findAllById(venueIds).stream()
                .collect(Collectors.toMap(Venue::getVenueId, Venue::getName));

        return slots.stream()
                .map(s -> new InternalSlotResponse(
                        s.getSlotId(), s.getVenueId(),
                        venueNames.getOrDefault(s.getVenueId(), null),
                        s.getStartTime(), s.getStatus().name()))
                .toList();
    }
}
