package com.seatlock.venue.repository;

import com.seatlock.venue.domain.Venue;
import com.seatlock.venue.domain.VenueStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VenueRepository extends JpaRepository<Venue, UUID> {
    List<Venue> findByStatus(VenueStatus status);
}
