package com.seatlock.venue.controller;

import com.seatlock.common.exception.SlotNotFoundException;
import com.seatlock.venue.domain.Slot;
import com.seatlock.venue.domain.SlotStatus;
import com.seatlock.venue.domain.Venue;
import com.seatlock.venue.domain.VenueStatus;
import com.seatlock.venue.dto.InternalSlotResponse;
import com.seatlock.venue.repository.SlotRepository;
import com.seatlock.venue.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalSlotControllerTest {

    @Mock SlotRepository slotRepository;
    @Mock VenueRepository venueRepository;

    InternalSlotController controller;

    UUID venueId = UUID.randomUUID();
    UUID slotId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new InternalSlotController(slotRepository, venueRepository);
    }

    @Test
    void getSlotsByIds_includesVenueName_whenVenueExists() {
        Slot slot = slot(slotId, venueId);
        Venue venue = venue(venueId, "Grand Arena");

        when(slotRepository.findAllById(anyList())).thenReturn(List.of(slot));
        when(venueRepository.findAllById(any())).thenReturn(List.of(venue));

        List<InternalSlotResponse> result = controller.getSlotsByIds(List.of(slotId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).slotId()).isEqualTo(slotId);
        assertThat(result.get(0).venueId()).isEqualTo(venueId);
        assertThat(result.get(0).venueName()).isEqualTo("Grand Arena");
        assertThat(result.get(0).startTime()).isNotNull();
        assertThat(result.get(0).status()).isEqualTo("AVAILABLE");
    }

    @Test
    void getSlotsByIds_venueNameNull_whenVenueNotFound() {
        Slot slot = slot(slotId, venueId);

        when(slotRepository.findAllById(anyList())).thenReturn(List.of(slot));
        when(venueRepository.findAllById(any())).thenReturn(List.of()); // venue missing

        List<InternalSlotResponse> result = controller.getSlotsByIds(List.of(slotId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).venueName()).isNull();
    }

    @Test
    void getSlotsByIds_venueNameNull_whenVenueIdIsNull() {
        Slot slotWithoutVenue = slot(slotId, null); // slot not linked to a venue

        when(slotRepository.findAllById(anyList())).thenReturn(List.of(slotWithoutVenue));
        when(venueRepository.findAllById(any())).thenReturn(List.of());

        List<InternalSlotResponse> result = controller.getSlotsByIds(List.of(slotId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).venueId()).isNull();
        assertThat(result.get(0).venueName()).isNull();
    }

    @Test
    void getSlotsByIds_throwsSlotNotFoundException_whenOneSlotMissing() {
        // Requesting 2 slots but only 1 found
        when(slotRepository.findAllById(anyList())).thenReturn(List.of(slot(slotId, venueId)));

        assertThatThrownBy(() -> controller.getSlotsByIds(List.of(slotId, UUID.randomUUID())))
                .isInstanceOf(SlotNotFoundException.class);
    }

    @Test
    void getSlotsByIds_multipleSlotsMultipleVenues_batchFetchedInOneCall() {
        UUID venueId2 = UUID.randomUUID();
        UUID slotId2  = UUID.randomUUID();

        Slot s1 = slot(slotId, venueId);
        Slot s2 = slot(slotId2, venueId2);
        Venue v1 = venue(venueId, "Venue A");
        Venue v2 = venue(venueId2, "Venue B");

        when(slotRepository.findAllById(anyList())).thenReturn(List.of(s1, s2));
        when(venueRepository.findAllById(any())).thenReturn(List.of(v1, v2));

        List<InternalSlotResponse> result = controller.getSlotsByIds(List.of(slotId, slotId2));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(InternalSlotResponse::venueName)
                .containsExactlyInAnyOrder("Venue A", "Venue B");
    }

    // --- helpers ---

    private Slot slot(UUID slotId, UUID venueId) {
        Slot s = new Slot();
        s.setVenueId(venueId);
        s.setStartTime(Instant.now().plus(1, ChronoUnit.HOURS));
        s.setStatus(SlotStatus.AVAILABLE);
        try {
            var field = Slot.class.getDeclaredField("slotId");
            field.setAccessible(true);
            field.set(s, slotId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    private Venue venue(UUID venueId, String name) {
        Venue v = new Venue();
        v.setName(name);
        v.setStatus(VenueStatus.ACTIVE);
        try {
            var field = Venue.class.getDeclaredField("venueId");
            field.setAccessible(true);
            field.set(v, venueId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return v;
    }
}
