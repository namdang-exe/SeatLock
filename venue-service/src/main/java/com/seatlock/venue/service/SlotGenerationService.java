package com.seatlock.venue.service;

import com.seatlock.venue.domain.Slot;
import com.seatlock.venue.repository.SlotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SlotGenerationService {

    private final SlotRepository slotRepository;

    @Value("${seatlock.slot.duration-minutes:60}")
    private int durationMinutes;

    public SlotGenerationService(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    @Transactional
    public List<Slot> generate(UUID venueId, LocalDate fromDate, LocalDate toDate) {
        Instant rangeStart = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant rangeEnd = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Set<Instant> existing = slotRepository
                .findByVenueIdAndStartTimeBetween(venueId, rangeStart, rangeEnd)
                .stream()
                .map(Slot::getStartTime)
                .collect(Collectors.toSet());

        List<Slot> toInsert = new ArrayList<>();
        LocalDate current = fromDate;
        while (!current.isAfter(toDate)) {
            DayOfWeek dow = current.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                LocalTime time = LocalTime.of(9, 0);
                LocalTime end = LocalTime.of(17, 0);
                while (time.isBefore(end)) {
                    Instant startTime = current.atTime(time).toInstant(ZoneOffset.UTC);
                    if (!existing.contains(startTime)) {
                        Slot slot = new Slot();
                        slot.setVenueId(venueId);
                        slot.setStartTime(startTime);
                        toInsert.add(slot);
                    }
                    time = time.plusMinutes(durationMinutes);
                }
            }
            current = current.plusDays(1);
        }

        return slotRepository.saveAll(toInsert);
    }
}
