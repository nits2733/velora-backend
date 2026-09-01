package com.velora.backend.repository;

import com.velora.backend.entity.BookingInspirationImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingInspirationImageRepository extends JpaRepository<BookingInspirationImage, Long> {
    List<BookingInspirationImage> findByBookingIdOrderByCreatedAtAsc(Long bookingId);
}
