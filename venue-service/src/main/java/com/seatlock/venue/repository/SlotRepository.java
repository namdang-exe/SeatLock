package com.seatlock.venue.repository;

import com.seatlock.venue.domain.Slot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<Slot, UUID> {

    /** Slots for a specific UTC day — used by GET /venues/{id}/slots?date= */
    @Query("SELECT s FROM Slot s WHERE s.venueId = :venueId AND s.startTime >= :dayStart AND s.startTime < :dayEnd ORDER BY s.startTime ASC")
    List<Slot> findByVenueIdAndDay(@Param("venueId") UUID venueId,
                                   @Param("dayStart") Instant dayStart,
                                   @Param("dayEnd") Instant dayEnd);

    /** All slots for a venue ordered by time — used when no date filter is given */
    List<Slot> findByVenueIdOrderByStartTimeAsc(UUID venueId);

    /** All slots in a time range — used for duplicate checking during generation */
    List<Slot> findByVenueIdAndStartTimeBetween(UUID venueId, Instant start, Instant end);
}
