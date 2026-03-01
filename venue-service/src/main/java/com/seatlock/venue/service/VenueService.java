package com.seatlock.venue.service;

import com.seatlock.venue.domain.Slot;
import com.seatlock.venue.domain.Venue;
import com.seatlock.venue.domain.VenueStatus;
import com.seatlock.venue.dto.CreateVenueRequest;
import com.seatlock.venue.dto.GenerateSlotsRequest;
import com.seatlock.venue.dto.SlotResponse;
import com.seatlock.venue.dto.UpdateVenueStatusRequest;
import com.seatlock.venue.dto.VenueResponse;
import com.seatlock.venue.exception.VenueNotFoundException;
import com.seatlock.venue.repository.SlotRepository;
import com.seatlock.venue.repository.VenueRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class VenueService {

    private final VenueRepository venueRepository;
    private final SlotRepository slotRepository;
    private final SlotGenerationService slotGenerationService;

    @Value("${seatlock.slot.duration-minutes:60}")
    private int slotDurationMinutes;

    public VenueService(VenueRepository venueRepository,
                        SlotRepository slotRepository,
                        SlotGenerationService slotGenerationService) {
        this.venueRepository = venueRepository;
        this.slotRepository = slotRepository;
        this.slotGenerationService = slotGenerationService;
    }

    public List<VenueResponse> getActiveVenues() {
        return venueRepository.findByStatus(VenueStatus.ACTIVE)
                .stream()
                .map(this::toVenueResponse)
                .toList();
    }

    @Transactional
    public VenueResponse createVenue(CreateVenueRequest req) {
        Venue venue = new Venue();
        venue.setName(req.name());
        venue.setAddress(req.address());
        venue.setCity(req.city());
        venue.setState(req.state());
        return toVenueResponse(venueRepository.save(venue));
    }

    @Transactional
    public VenueResponse updateVenueStatus(UUID venueId, UpdateVenueStatusRequest req) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new VenueNotFoundException(venueId));
        venue.setStatus(req.status());
        return toVenueResponse(venueRepository.save(venue));
    }

    public List<SlotResponse> getSlots(UUID venueId, LocalDate date, String statusFilter) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new VenueNotFoundException(venueId));
        if (venue.getStatus() == VenueStatus.INACTIVE) {
            throw new VenueNotFoundException(venueId);
        }

        List<Slot> slots;
        if (date != null) {
            Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            slots = slotRepository.findByVenueIdAndDay(venueId, dayStart, dayEnd);
        } else {
            slots = slotRepository.findByVenueIdOrderByStartTimeAsc(venueId);
        }

        return slots.stream()
                .filter(s -> statusFilter == null || s.getStatus().name().equals(statusFilter))
                .map(this::toSlotResponse)
                .toList();
    }

    @Transactional
    public List<SlotResponse> generateSlots(UUID venueId, GenerateSlotsRequest req) {
        venueRepository.findById(venueId)
                .orElseThrow(() -> new VenueNotFoundException(venueId));
        return slotGenerationService.generate(venueId, req.fromDate(), req.toDate())
                .stream()
                .map(this::toSlotResponse)
                .toList();
    }

    private VenueResponse toVenueResponse(Venue v) {
        return new VenueResponse(
                v.getVenueId().toString(),
                v.getName(),
                v.getAddress(),
                v.getCity(),
                v.getState(),
                v.getStatus().name()
        );
    }

    private SlotResponse toSlotResponse(Slot s) {
        Instant endTime = s.getStartTime().plus(slotDurationMinutes, ChronoUnit.MINUTES);
        return new SlotResponse(
                s.getSlotId().toString(),
                s.getVenueId().toString(),
                s.getStartTime(),
                endTime,
                s.getStatus().name()
        );
    }
}
