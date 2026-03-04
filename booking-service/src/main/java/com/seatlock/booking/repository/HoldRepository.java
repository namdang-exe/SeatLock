package com.seatlock.booking.repository;

import com.seatlock.booking.domain.Hold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {
}
