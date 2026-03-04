package com.seatlock.booking.repository;

import com.seatlock.booking.domain.Hold;
import com.seatlock.booking.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    List<Hold> findBySessionIdAndStatus(UUID sessionId, HoldStatus status);
}
