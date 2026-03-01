package com.seatlock.venue.service;

import com.seatlock.venue.domain.Slot;
import com.seatlock.venue.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlotGenerationServiceTest {

    @Mock
    private SlotRepository slotRepository;

    @InjectMocks
    private SlotGenerationService service;

    private final UUID venueId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "durationMinutes", 60);
        // lenient: skipsExistingSlots overrides the findByVenueIdAndStartTimeBetween stub
        lenient().when(slotRepository.findByVenueIdAndStartTimeBetween(eq(venueId), any(), any()))
                .thenReturn(Collections.emptyList());
        lenient().when(slotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void generate_weekdayOnly_skipsWeekends() {
        // 2024-02-19 (Mon) to 2024-02-25 (Sun) — 5 weekdays, 2 weekend days
        LocalDate from = LocalDate.of(2024, 2, 19);
        LocalDate to = LocalDate.of(2024, 2, 25);

        List<Slot> result = service.generate(venueId, from, to);

        // 5 weekdays × 8 slots/day (09:00–17:00 in 60-min blocks)
        assertThat(result).hasSize(40);
    }

    @Test
    void generate_correctTimes_nineToFiveInSixtyMinBlocks() {
        // Single Monday
        LocalDate monday = LocalDate.of(2024, 2, 19);

        List<Slot> result = service.generate(venueId, monday, monday);

        assertThat(result).hasSize(8);
        assertThat(result.get(0).getStartTime())
                .isEqualTo(monday.atTime(9, 0).toInstant(ZoneOffset.UTC));
        assertThat(result.get(7).getStartTime())
                .isEqualTo(monday.atTime(16, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    void generate_skipsExistingSlots() {
        LocalDate monday = LocalDate.of(2024, 2, 19);

        // Simulate the 09:00 slot already existing
        Slot existing = new Slot();
        existing.setVenueId(venueId);
        existing.setStartTime(monday.atTime(9, 0).toInstant(ZoneOffset.UTC));

        when(slotRepository.findByVenueIdAndStartTimeBetween(eq(venueId), any(), any()))
                .thenReturn(List.of(existing));

        List<Slot> result = service.generate(venueId, monday, monday);

        // 8 slots minus the one that already exists = 7
        assertThat(result).hasSize(7);
        assertThat(result).noneMatch(s ->
                s.getStartTime().equals(monday.atTime(9, 0).toInstant(ZoneOffset.UTC)));
    }

    @Test
    void generate_saturdaySunday_producesNoSlots() {
        LocalDate saturday = LocalDate.of(2024, 2, 17);
        LocalDate sunday = LocalDate.of(2024, 2, 18);

        List<Slot> saturday_result = service.generate(venueId, saturday, saturday);
        List<Slot> sunday_result = service.generate(venueId, sunday, sunday);

        assertThat(saturday_result).isEmpty();
        assertThat(sunday_result).isEmpty();
    }
}