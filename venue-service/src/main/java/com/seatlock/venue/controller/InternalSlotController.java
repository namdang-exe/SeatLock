package com.seatlock.venue.controller;

import com.seatlock.common.exception.SlotNotFoundException;
import com.seatlock.venue.domain.Slot;
import com.seatlock.venue.dto.InternalSlotResponse;
import com.seatlock.venue.repository.SlotRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal")
public class InternalSlotController {

    private final SlotRepository slotRepository;

    public InternalSlotController(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    @GetMapping("/slots")
    public List<InternalSlotResponse> getSlotsByIds(@RequestParam List<UUID> ids) {
        List<Slot> slots = slotRepository.findAllById(ids);
        if (slots.size() != ids.size()) {
            throw new SlotNotFoundException("One or more requested slots not found");
        }
        return slots.stream()
                .map(s -> new InternalSlotResponse(s.getSlotId(), s.getVenueId(), s.getStartTime(), s.getStatus().name()))
                .toList();
    }
}
