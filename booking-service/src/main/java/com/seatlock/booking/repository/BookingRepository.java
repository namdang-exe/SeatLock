package com.seatlock.booking.repository;

import com.seatlock.booking.domain.Booking;
import com.seatlock.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findBySessionIdAndStatus(UUID sessionId, BookingStatus status);
}
