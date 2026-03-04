package com.seatlock.booking.repository;

import com.seatlock.booking.domain.Hold;
import com.seatlock.booking.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    List<Hold> findBySessionIdAndStatus(UUID sessionId, HoldStatus status);

    @Modifying
    @Transactional
    @Query("UPDATE Hold h SET h.status = :newStatus WHERE h.sessionId = :sessionId AND h.status = :currentStatus")
    int updateStatusBySessionId(@Param("sessionId") UUID sessionId,
                                @Param("currentStatus") HoldStatus currentStatus,
                                @Param("newStatus") HoldStatus newStatus);
}
