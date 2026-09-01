package com.velora.backend.repository;

import com.velora.backend.entity.BookingTimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingTimelineEventRepository extends JpaRepository<BookingTimelineEvent, Long> {
    List<BookingTimelineEvent> findByBookingIdOrderByCreatedAtAsc(Long bookingId);
}
